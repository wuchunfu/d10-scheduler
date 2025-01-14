package com.github.krystalics.d10.scheduler.core.init;


import com.github.krystalics.d10.scheduler.common.constant.CommonConstants;
import com.github.krystalics.d10.scheduler.common.constant.FrequencyGranularity;
import com.github.krystalics.d10.scheduler.common.utils.CronUtils;
import com.github.krystalics.d10.scheduler.common.utils.DateUtils;
import com.github.krystalics.d10.scheduler.core.init.time.ParamExpressionUtils;
import com.github.krystalics.d10.scheduler.core.pool.InitExecutors;
import com.github.krystalics.d10.scheduler.dao.entity.Instance;
import com.github.krystalics.d10.scheduler.dao.entity.InstanceRely;
import com.github.krystalics.d10.scheduler.dao.entity.Task;
import com.github.krystalics.d10.scheduler.dao.entity.TaskRely;
import com.github.krystalics.d10.scheduler.dao.entity.Version;
import com.github.krystalics.d10.scheduler.dao.mapper.InstanceMapper;
import com.github.krystalics.d10.scheduler.dao.mapper.InstanceRelyMapper;
import com.github.krystalics.d10.scheduler.dao.mapper.TaskMapper;
import com.github.krystalics.d10.scheduler.dao.mapper.TaskRelyMapper;
import com.github.krystalics.d10.scheduler.dao.mapper.VersionMapper;
import com.github.krystalics.d10.scheduler.dao.qm.TaskQM;
import com.github.krystalics.d10.scheduler.dao.qm.TaskRelyQM;
import lombok.extern.slf4j.Slf4j;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.leader.LeaderLatch;
import org.apache.curator.framework.recipes.locks.InterProcessSemaphoreMutex;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;


/**
 * @author linjiabao001
 * @date 2021/10/8
 * @description
 */
@Component
@Slf4j
public class Initiation {

    /**
     * 记录任务id今天生成的版本，避免后面疯狂查库
     */
    private static final ConcurrentHashMap<Long, CopyOnWriteArraySet<Version>> taskIdAndVersionsMap = new ConcurrentHashMap<>();

    private static final ConcurrentHashMap<Long, Task> tasksMap = new ConcurrentHashMap<>();


    @Autowired
    private TaskMapper taskMapper;

    @Autowired
    private VersionMapper versionMapper;

    @Autowired
    private InstanceMapper instanceMapper;

    @Autowired
    private TaskRelyMapper taskRelyMapper;

    @Autowired
    private InstanceRelyMapper instanceRelyMapper;

    private CountDownLatch latch;

    Date todayMin;

    Date todayMax;

    @Autowired
    private CuratorFramework client;

    @Autowired
    private LeaderLatch leaderLatch;

    /**
     * mysql表时间字段的值如果为null、那么在范围搜索的时候会直接返回 false
     * 例如 next_instance_time<='2021-10-10 00:00:00' 一条记录都搜不到
     *
     * 每天0点触发实例化
     * @throws InterruptedException
     */
    @Scheduled(cron = "0 0 0 * * *")
    public void init() throws Exception {
        if (leaderLatch.hasLeadership()) {
            InterProcessSemaphoreMutex mutex = new InterProcessSemaphoreMutex(client, CommonConstants.LOCK_INIT);
            try {
                mutex.acquire();
                log.info("get init lock!");

                InitExecutors.startEvenShutdown();

                Date now = new Date();
                todayMin = getMinOrMaxDay(now, true);
                todayMax = getMinOrMaxDay(now, false);

                log.info("scheduler start init the tasks, now is {}、and today min is {},max is {}", now, todayMin, todayMax);
                TaskQM taskQM = new TaskQM();
                taskQM.setState(2);
                taskQM.setNextInstanceTime(todayMax);

                List<Task> tasks = taskMapper.list(taskQM);

                latch = new CountDownLatch(tasks.size());
                tasks.forEach((task) -> {
                    tasksMap.put(task.getTaskId(), task);
                    InitExecutors.submit(() -> initTask(task));
                });
                latch.await();
                log.info("init versions and instance finished , cost " + ((System.currentTimeMillis() / 1000) - now.getTime()) + " s");

                latch = new CountDownLatch(tasks.size());
                tasks.forEach((task) -> InitExecutors.submit(() -> initRely(task)));
                latch.await();

                log.info("init relies finished , cost " + (((System.currentTimeMillis() / 1000) - now.getTime()) + " s"));
            } catch (Exception e) {
                log.error("initiation exception、lets shutdown the thread pool!", e);
                InitExecutors.shutdownNow();
            } finally {
                mutex.release();
            }
        }

    }

    public void initTask(Task task) {
        Date date = todayMin;
        try {
            //小时级任务，需要生成24个版本
            if (FrequencyGranularity.HOUR.getValue() == task.getFrequency()) {
                for (int i = 0; i < 24; i++) {
                    date = initVersionAndInstance(task, date);
                }
            } else {
                date = initVersionAndInstance(task, date);
            }
            //下一次实例化的时间
            ZonedDateTime zonedDateTime = CronUtils.nextExecutionDate(ZonedDateTime.ofInstant(date.toInstant(), ZoneId.of(CommonConstants.SYSTEM_TIME_ZONE)), task.getCrontab());
            task.setNextInstanceTime(Date.from(zonedDateTime.toInstant()));
            taskMapper.update(task);
        } catch (Exception e) {
            log.error("init task error where task id = " + task.getTaskId() + " and task name = " + task.getTaskName(), e);
        } finally {
            latch.countDown();
        }

        log.info("init task where task id =" + task.getTaskId() + " and task name = " + task.getTaskName() + " success!!!");
    }

    /**
     * @param task 任务
     * @param date 参考时间
     * @return task具体执行的时间
     */
    private Date initVersionAndInstance(Task task, Date date) {
        ZonedDateTime zonedDateTime = CronUtils.nextExecutionDate(ZonedDateTime.ofInstant(date.toInstant(), ZoneId.of(CommonConstants.SYSTEM_TIME_ZONE)), task.getCrontab());
        date = Date.from(zonedDateTime.toInstant());
        if (date.after(todayMax)) {
            return null;
        }
        String versionNo = DateUtils.versionNo(zonedDateTime, task.getFrequency());
        insertVersionAndInstance(task, versionNo, date);
        return date;
    }

    /**
     * 先查看数据库中有没有该版本，用于服务重启时的容错
     * 再查看数据库中有没有该实例，用于服务重启时的容错
     *
     */
    public Instance insertVersionAndInstance(Task task, String versionNo, Date startTimeTheory) {
        Version version = versionMapper.findByTaskIdAndVersionNo(task.getTaskId(), versionNo);
        if (version == null) {
            version = generateVersion(task, versionNo);
            versionMapper.insert(version);
        }
        long versionId = version.getVersionId();

        Instance instance = instanceMapper.findLastInstanceByVersionId(versionId);

        if (instance == null) {
            instance = generateInstance(task, versionId, versionNo, startTimeTheory);
            instanceMapper.insert(instance);
        }
        long instanceId = instance.getInstanceId();

        version.setLastInstanceId(instanceId);
        versionMapper.update(version);

        CopyOnWriteArraySet<Version> versions = taskIdAndVersionsMap.getOrDefault(task.getTaskId(), new CopyOnWriteArraySet<Version>());
        versions.add(version);
        taskIdAndVersionsMap.put(task.getTaskId(), versions);

        return instance;
    }

    private Version generateVersion(Task task, String versionNo) {
        Version version = new Version();
        version.setTaskId(task.getTaskId());
        version.setRetryRemainTimes(task.getRetryTimes());
        version.setVersionNo(versionNo);
        return version;
    }


    public Instance generateInstance(Task task, long versionId, String versionNo, Date startTimeTheory) {
        Instance instance = new Instance();
        instance.setJobConf(replaceParam(task.getJobConf(), versionNo));
        instance.setState(1);
        instance.setTaskId(task.getTaskId());
        instance.setVersionId(versionId);
        instance.setStartTimeTheory(startTimeTheory);
        return instance;
    }

    /**
     * 将时间参数按照参考时间【版本号 versionNo】替换成真实的时间
     *
     * @param jobConf
     * @param versionNo
     * @return
     */
    public String replaceParam(String jobConf, String versionNo) {
        DateTime versionDateTime = DateTimeFormat.forPattern("yyyyMMddHHmmss").parseDateTime(versionNo);
        return ParamExpressionUtils.handleTimeExpression(jobConf, versionDateTime.toString("yyyy-MM-dd HH:mm:ss"));
    }

    public void initRely(Task task) {
        long taskId = task.getTaskId();

        Set<Version> versions = taskIdAndVersionsMap.get(taskId);

        initRelies(taskId, todayMin, versions);

        log.info("version rely generated where task id is {} ", taskId);
        latch.countDown();
    }

    public void initRelies(long taskId, Date date, Set<Version> versions) {
        TaskRelyQM taskRelyQM = new TaskRelyQM();
        taskRelyQM.setTaskId(taskId);
        List<TaskRely> upRelies = taskRelyMapper.list(taskRelyQM);

        for (Version version : versions) {
            for (TaskRely upRely : upRelies) {
                fillRely(version, upRely, date);
            }
        }
    }

    public void fillRely(Version version, TaskRely rely, Date date) {
        long upTaskId = rely.getUpTaskId();

        Task upTask = tasksMap.get(upTaskId);

        //说明该上游下线了,或者任务周期大于1天 且执行时间不在当日内;
        if (upTask == null) {
            //从数据库中获取
            TaskQM taskQM = new TaskQM();
            taskQM.setTaskId(upTaskId);
            upTask = taskMapper.list(taskQM).get(0);
            if (upTask == null) {
                log.info("the up upTask not exist, which task_id is {}", upTaskId);
                return;
            }
        }

        final List<ZonedDateTime> upTaskDates = CronUtils.rangeExecutionDate(ZonedDateTime.ofInstant(date.toInstant(), ZoneId.of(CommonConstants.SYSTEM_TIME_ZONE)), upTask.getCrontab(), rely.getOffset(), rely.getCnt());
        //构造上游的实例依赖
        for (ZonedDateTime upTaskDate : upTaskDates) {
            final String versionNo = DateUtils.versionNo(upTaskDate, upTask.getFrequency());
            InstanceRely instanceRely = new InstanceRely();
            instanceRely.setInstanceId(version.getLastInstanceId());
            instanceRely.setUpTaskId(upTaskId);
            instanceRely.setUpVersionNo(versionNo);

            instanceRelyMapper.insert(instanceRely);
        }
    }

    public Date getMinOrMaxDay(Date date, boolean min) {
        LocalDateTime localDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(date.getTime()), ZoneId.systemDefault());
        LocalDateTime dateTime;
        if (min) {
            dateTime = localDateTime.minusDays(1).with(LocalTime.MAX);
        } else {
            dateTime = localDateTime.with(LocalTime.MAX);
        }
        return Date.from(dateTime.atZone(ZoneId.systemDefault()).toInstant());
    }

}
