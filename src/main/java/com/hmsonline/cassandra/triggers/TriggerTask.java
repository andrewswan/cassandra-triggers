package com.hmsonline.cassandra.triggers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.hmsonline.cassandra.triggers.dao.CommitLog;
import com.hmsonline.cassandra.triggers.dao.ConfigurationStore;

public class TriggerTask implements Runnable {
    private static Logger logger = LoggerFactory.getLogger(TriggerTask.class);
    private static final String THREAD_POOL_SIZE_PARAM = "cassandra.triggers.pool.size";
    private static final int MAX_QUEUE_SIZE = 500;

    private int threadPoolSize = 20;
    private List<Thread> threadPool = new ArrayList<Thread>();
    private BlockingQueue<LogEntry> workQueue = null;
    private ProcessingManager processing;

    public TriggerTask() {
        String threadPoolParam = System.getProperty(THREAD_POOL_SIZE_PARAM);
        if (threadPoolParam != null){
            threadPoolSize = Integer.parseInt(threadPoolParam);            
        }
        processing = new ProcessingManager();
        workQueue = new ArrayBlockingQueue<LogEntry>(MAX_QUEUE_SIZE);
        // Spinning up new thread pool
        logger.debug("Spawning [" + threadPoolSize + "] threads for commit log processing.");
        for (int i = 0; i < threadPoolSize; i++) {
            TriggerExecutionThread runnable = new TriggerExecutionThread(workQueue, processing);
            Thread thread = new Thread(runnable);
            thread.setDaemon(true);
            threadPool.add(thread);
            thread.start();
        }
    }

    public void run() {
        boolean gotUpdates = false;
        while (true) {
            gotUpdates = false;
            try {
                if (ConfigurationStore.getStore().isCommitLogEnabled()) {
                    List<LogEntry> logEntries = CommitLog.getCommitLog().getPending();
                    if (logger.isDebugEnabled() && logEntries != null) {
                        logger.debug("Processing [" + logEntries.size() + "] logEntries.");
                    }
                    for (LogEntry logEntry : logEntries) {
                        if (LogEntryStatus.COMMITTED.equals(logEntry.getStatus()) && (!processing.isAlreadyBeingProcessed(logEntry.getUuid()))) {
                            gotUpdates = true;
                            workQueue.put(logEntry);
                        }
                    }
                } else {
                    logger.debug("Skipping trigger execution because commit log is disabled.");
                }
            } catch (Throwable t) {
                logger.warn("Could not execute triggers [" + t.getMessage() + "]");
                logger.debug("Cause for not executing triggers.", t);
            }
            if (!gotUpdates) {
                try {
                    Thread.sleep(1000);
                } catch (Exception e) {
                    logger.error("Couldn't sleep.", e);
                }
            }
        }
    }

}
