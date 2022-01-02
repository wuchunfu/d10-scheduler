package com.github.krystalics.d10.scheduler.core;

import com.github.krystalics.d10.scheduler.common.constant.CommonConstants;
import com.github.krystalics.d10.scheduler.common.utils.IPUtils;
import com.github.krystalics.d10.scheduler.core.schedule.DistributedScheduler;
import com.github.krystalics.d10.scheduler.registry.service.impl.RebalanceServiceImpl;
import com.github.krystalics.d10.scheduler.registry.service.impl.ZookeeperServiceImpl;
import com.github.krystalics.d10.scheduler.registry.zk.listener.AllNodesChangeListener;
import com.github.krystalics.d10.scheduler.registry.zk.listener.ElectionListener;
import com.github.krystalics.d10.scheduler.registry.zk.listener.LeaderChangeListener;
import com.github.krystalics.d10.scheduler.registry.zk.listener.LiveNodesChangeListener;
import com.github.krystalics.d10.scheduler.registry.zk.listener.ShardListener;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.cache.CuratorCache;
import org.apache.curator.framework.recipes.cache.CuratorCacheListener;
import org.apache.curator.framework.recipes.leader.LeaderLatch;
import org.apache.zookeeper.CreateMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * @author krysta
 */
@Component
@Slf4j
public class StartupRunner implements CommandLineRunner {

    @Value("${server.port}")
    private int port;

    @Autowired
    private CuratorFramework client;

    @Autowired
    private ElectionListener electionListener;

    @Autowired
    private LeaderChangeListener leaderChangeListener;

    @Autowired
    private AllNodesChangeListener allNodesChangeListener;

    @Autowired
    private LiveNodesChangeListener liveNodesChangeListener;

    @Autowired
    private ShardListener shardListener;

    @Autowired
    private ZookeeperServiceImpl zookeeperService;

    @Autowired
    private RebalanceServiceImpl rebalanceService;

    @Bean
    public LeaderLatch leaderLatch() {
        String address = IPUtils.getHost() + ":" + port;
        return new LeaderLatch(client, CommonConstants.ZK_ELECTION, address, LeaderLatch.CloseMode.NOTIFY_LEADER);
    }

    String address = "";

    @Autowired
    private LeaderLatch leaderLatch;

    @Autowired
    private DistributedScheduler distributedScheduler;

    /**
     * 作为leader的话、会将自身的id或者 ip 写进 /election节点中
     * 阻塞至成为新的leader后会进行 shard 重新分片的工作
     * 再然后进行实例化的工作
     * 不过不成为leader也不应该影响服务的正常启动，所以会额外放在一个线程去执行leader的选举
     *
     * @param args
     * @throws Exception
     */
    @Override
    public void run(String... args) throws Exception {
        new Thread(new Runnable() {
            @SneakyThrows
            @Override
            public void run() {
                address = IPUtils.getHost() + ":" + port;
                log.info("init action begin! this node address is {}", address);
                StartupRunner.this.initZkPaths();
                StartupRunner.this.initCuratorCaches();
                log.info("try to be a leader!");
                leaderLatch.addListener(electionListener);
                leaderLatch.start();

                leaderLatch.await();
                rebalanceService.rebalance(address);

//                distributedScheduler.init();
            }
        }, "election").start();
    }

    public void initZkPaths() throws Exception {
        log.info("create zk init paths if need!");
        zookeeperService.createNodeIfNotExist(CommonConstants.ZK_LEADER, "leader ip", CreateMode.PERSISTENT);
        zookeeperService.createNodeIfNotExist(CommonConstants.ZK_LIVE_NODES, "cluster live ips", CreateMode.PERSISTENT);
        zookeeperService.createNodeIfNotExist(CommonConstants.ZK_ALL_NODES, "cluster all ips", CreateMode.PERSISTENT);
        zookeeperService.createNodeIfNotExist(CommonConstants.ZK_ALL_NODES + "/" + address, address, CreateMode.PERSISTENT);
        //在live中为临时节点
        zookeeperService.createNodeIfNotExist(CommonConstants.ZK_LIVE_NODES + "/" + address, address, CreateMode.EPHEMERAL);
    }

    /**
     * curator cache中的 afterInitialized 可以让listener在节点初始化完后再加入 监听，
     * <p>
     * 没加它之前：之前存在的node、都是会触发对应的 child_add事件
     * 这里加了afterInitialized，之前存在的节点对它来说不影响。避免触发对应的事件
     */
    public void initCuratorCaches() {
        log.info("create listeners for nodes changed!");

        CuratorCache leaderChangeCache = CuratorCache.build(client, CommonConstants.ZK_LEADER);
        leaderChangeCache.listenable().addListener(leaderChangeListener);

        CuratorCache shardCache = CuratorCache.build(client, CommonConstants.ZK_SHARD_NODE);
        shardCache.listenable().addListener(shardListener);

        CuratorCache allNodesCache = CuratorCache.build(client, CommonConstants.ZK_ALL_NODES);
        CuratorCacheListener allNodesCacheListener = CuratorCacheListener.builder().afterInitialized().forPathChildrenCache(CommonConstants.ZK_ALL_NODES, client, allNodesChangeListener).build();
        allNodesCache.listenable().addListener(allNodesCacheListener);

        CuratorCache liveNodesCache = CuratorCache.build(client, CommonConstants.ZK_LIVE_NODES);
        CuratorCacheListener liveNodesCacheListener = CuratorCacheListener.builder().afterInitialized().forPathChildrenCache(CommonConstants.ZK_LIVE_NODES, client, liveNodesChangeListener).build();
        liveNodesCache.listenable().addListener(liveNodesCacheListener);

        leaderChangeCache.start();
        shardCache.start();
        allNodesCache.start();
        liveNodesCache.start();
    }

    public void createNode(String path, String data, CreateMode mode) throws Exception {
        if (client.checkExists().forPath(path) != null) {
            return;
        }
        client.create().creatingParentsIfNeeded()
                .withMode(mode)
                .forPath(path, data.getBytes(StandardCharsets.UTF_8));
    }


}
