package cn.oneachina.captureSpawn.scheduler;

public interface ScheduledHandle {
    void cancel();

    boolean isCancelled();
}
