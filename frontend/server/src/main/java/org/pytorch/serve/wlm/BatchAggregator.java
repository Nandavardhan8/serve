package org.pytorch.serve.wlm;

import static org.pytorch.serve.util.messages.RequestInput.TS_STREAM_NEXT;

import java.util.LinkedHashMap;
import java.util.Map;
import org.pytorch.serve.job.Job;
import org.pytorch.serve.util.messages.BaseModelRequest;
import org.pytorch.serve.util.messages.ModelInferenceRequest;
import org.pytorch.serve.util.messages.ModelLoadModelRequest;
import org.pytorch.serve.util.messages.ModelWorkerResponse;
import org.pytorch.serve.util.messages.Predictions;
import org.pytorch.serve.util.messages.RequestInput;
import org.pytorch.serve.util.messages.WorkerCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BatchAggregator {

    private static final Logger logger = LoggerFactory.getLogger(BatchAggregator.class);

    private Model model;
    private Map<String, Job> jobs;

    public BatchAggregator(Model model) {
        this.model = model;
        jobs = new LinkedHashMap<>();
    }

    public BaseModelRequest getRequest(String threadName, WorkerState state)
            throws InterruptedException {
        jobs.clear();

        ModelInferenceRequest req = new ModelInferenceRequest(model.getModelName());

        model.pollBatch(
                threadName, (state == WorkerState.WORKER_MODEL_LOADED) ? 0 : Long.MAX_VALUE, jobs);

        for (Job j : jobs.values()) {
            if (j.isControlCmd()) {
                if (jobs.size() > 1) {
                    throw new IllegalStateException(
                            "Received more than 1 control command. "
                                    + "Control messages should be processed/retrieved one at a time.");
                }
                RequestInput input = j.getPayload();
                int gpuId = -1;
                String gpu = input.getStringParameter("gpu");
                if (gpu != null) {
                    gpuId = Integer.parseInt(gpu);
                }
                return new ModelLoadModelRequest(model, gpuId);
            } else {
                if (j.getCmd() == WorkerCommands.STREAMPREDICT) {
                    req.setCommand(WorkerCommands.STREAMPREDICT);
                }
                j.setScheduled();
                req.addRequest(j.getPayload());
            }
        }
        return req;
    }

    public void sendResponse(ModelWorkerResponse message) {
        boolean jobDone = true;
        // TODO: Handle prediction level code
        if (message.getCode() == 200) {
            if (jobs.isEmpty()) {
                // this is from initial load.
                return;
            }
            for (Predictions prediction : message.getPredictions()) {
                String jobId = prediction.getRequestId();
                Job job = jobs.get(jobId);

                if (job == null) {
                    throw new IllegalStateException(
                            "Unexpected job in sendResponse() with 200 status code: " + jobId);
                }
                if (job.getCmd() == WorkerCommands.STREAMPREDICT
                        && prediction.getHeaders().get(TS_STREAM_NEXT).equals("true")) {
                    jobDone = false;
                }
                job.response(
                        prediction.getResp(),
                        prediction.getContentType(),
                        prediction.getStatusCode(),
                        prediction.getReasonPhrase(),
                        prediction.getHeaders());
            }

        } else {
            for (Map.Entry<String, Job> j : jobs.entrySet()) {

                if (j.getValue() == null) {
                    throw new IllegalStateException(
                            "Unexpected job in sendResponse() with non 200 status code: "
                                    + j.getKey());
                }
                j.getValue().sendError(message.getCode(), message.getMessage());
            }
        }
        if (jobDone) {
            jobs.clear();
        }
    }

    public void sendError(BaseModelRequest message, String error, int status) {
        if (message instanceof ModelLoadModelRequest) {
            logger.warn("Load model failed: {}, error: {}", message.getModelName(), error);
            return;
        }

        if (message != null) {
            ModelInferenceRequest msg = (ModelInferenceRequest) message;
            for (RequestInput req : msg.getRequestBatch()) {
                String requestId = req.getRequestId();
                Job job = jobs.remove(requestId);
                if (job == null) {
                    logger.error("Unexpected job in sendError(): " + requestId);
                } else {
                    job.sendError(status, error);
                }
            }
            if (!jobs.isEmpty()) {
                jobs.clear();
                logger.error("Not all jobs got an error response.");
            }
        } else {
            // Send the error message to all the jobs
            for (Map.Entry<String, Job> j : jobs.entrySet()) {
                String jobsId = j.getValue().getJobId();
                Job job = jobs.get(jobsId);

                if (job.isControlCmd()) {
                    job.sendError(status, error);
                } else {
                    // Data message can be handled by other workers.
                    // If batch has gone past its batch max delay timer?
                    model.addFirst(job);
                }
            }
        }
        jobs.clear();
    }
}