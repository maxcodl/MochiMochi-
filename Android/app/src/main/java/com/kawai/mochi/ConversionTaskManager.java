package com.kawai.mochi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** In-memory task store for Telegram conversions. */
public class ConversionTaskManager {

    public enum Status {
        QUEUED,
        RUNNING,
        SUCCEEDED,
        FAILED
    }

    public static class TaskPackResult {
        public final String identifier;
        public final String name;
        public final int stickerCount;
        public final boolean isAnimated;

        public TaskPackResult(String identifier, String name, int stickerCount, boolean isAnimated) {
            this.identifier = identifier;
            this.name = name;
            this.stickerCount = stickerCount;
            this.isAnimated = isAnimated;
        }
    }

    public static class TaskRecord {
        public final String taskId;
        public final long createdAt;
        public String url;
        public String author;
        public String packName;
        public Status status;
        public int done;
        public int total;
        public String error;
        public final List<String> logs;
        public final List<TaskPackResult> results;

        TaskRecord(String taskId, String url, String author, String packName) {
            this.taskId = taskId;
            this.createdAt = System.currentTimeMillis();
            this.url = url;
            this.author = author;
            this.packName = packName;
            this.status = Status.QUEUED;
            this.logs = new ArrayList<>();
            this.results = new ArrayList<>();
        }

        TaskRecord(TaskRecord other) {
            this.taskId = other.taskId;
            this.createdAt = other.createdAt;
            this.url = other.url;
            this.author = other.author;
            this.packName = other.packName;
            this.status = other.status;
            this.done = other.done;
            this.total = other.total;
            this.error = other.error;
            this.logs = new ArrayList<>(other.logs);
            this.results = new ArrayList<>(other.results);
        }
    }

    private static final ConversionTaskManager INSTANCE = new ConversionTaskManager();
    private final List<TaskRecord> tasks = new ArrayList<>();

    public static ConversionTaskManager getInstance() {
        return INSTANCE;
    }

    private ConversionTaskManager() {
    }

    public synchronized String createTask(String url, String author, String packName) {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        TaskRecord record = new TaskRecord(id, url, author, packName);
        tasks.add(record);
        return id;
    }

    public synchronized void markRunning(String taskId) {
        TaskRecord t = find(taskId);
        if (t == null) return;
        t.status = Status.RUNNING;
    }

    public synchronized void updateProgress(String taskId, int done, int total) {
        TaskRecord t = find(taskId);
        if (t == null) return;
        t.done = done;
        t.total = total;
    }

    public synchronized void appendLog(String taskId, String message) {
        TaskRecord t = find(taskId);
        if (t == null) return;
        t.logs.add(message);
        if (t.logs.size() > 400) {
            t.logs.remove(0);
        }
    }

    public synchronized void markSucceeded(String taskId, List<TaskPackResult> results) {
        TaskRecord t = find(taskId);
        if (t == null) return;
        t.status = Status.SUCCEEDED;
        t.error = null;
        t.results.clear();
        if (results != null) t.results.addAll(results);
    }

    public synchronized void markFailed(String taskId, String error) {
        TaskRecord t = find(taskId);
        if (t == null) return;
        t.status = Status.FAILED;
        t.error = error;
    }

    public synchronized TaskRecord getTask(String taskId) {
        TaskRecord t = find(taskId);
        return t == null ? null : new TaskRecord(t);
    }

    public synchronized List<TaskRecord> listTasks() {
        List<TaskRecord> copy = new ArrayList<>();
        for (TaskRecord t : tasks) copy.add(new TaskRecord(t));
        Collections.sort(copy, Comparator.comparingLong(a -> -a.createdAt));
        return copy;
    }

    private TaskRecord find(String taskId) {
        for (TaskRecord t : tasks) {
            if (t.taskId.equals(taskId)) return t;
        }
        return null;
    }
}
