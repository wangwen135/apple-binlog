package com.appleframework.binlog.zk.runner;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.recipes.leader.LeaderSelector;
import org.apache.curator.framework.recipes.leader.LeaderSelectorListener;
import org.apache.curator.framework.recipes.leader.LeaderSelectorListenerAdapter;
import org.apache.curator.framework.state.ConnectionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.appleframework.binlog.runner.ApplicationRunner;
import com.appleframework.binlog.zk.config.ZkConfig;
import com.appleframework.binlog.zk.election.ZkClientInfo;
import com.appleframework.binlog.zk.election.ZkClientUtil;

public class ZkApplicationRunner2 implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(ZkApplicationRunner2.class);

    // 用于选举Leader
    private CuratorFramework curatorClient;;
    private LeaderSelector selector;

    private boolean destory = false;

    private ApplicationRunner applicationRunner;

    public void setApplicationRunner(ApplicationRunner applicationRunner) {
        this.applicationRunner = applicationRunner;
    }

    /**
     * 是否有领导权
     */
    public boolean hasLeadership() {
        if (selector != null) {
            return selector.hasLeadership();
        }
        return false;
    }

    /**
     * 放弃领导权
     */
    public void relinquished() {
        logger.warn("主动放弃领导权...");
        applicationRunner.disconnect();
    }

    @Override
    public void run() {
        logger.warn("开始竞选Leader...");

        try {
            ZkClientInfo zkClientInfo = ZkConfig.getZkClientInfo();
            curatorClient = ZkClientUtil.getCuratorClient(zkClientInfo);

            LeaderSelectorListener listener = new LeaderSelectorListenerAdapter() {

                @Override
                public void takeLeadership(CuratorFramework client) throws Exception {
                    logger.warn("当前节点成功竞选为Leader，开始启动binlog监听...");
                    try {
                        applicationRunner.run();
                    } catch (Exception e) {
                        logger.error("binlog监听异常", e);
                        // 放弃领导权，并断开
                        relinquished();
                    } finally {
                        applicationRunner.disconnect();
                    }

                    Thread t = new Thread(new Runnable() {

                        @Override
                        public void run() {
                            if (selector != null) {
                                // 为了让出领导权，让其他节点有足够的时间获取领导权
                                try {
                                    Thread.sleep(10000);
                                } catch (InterruptedException e) {
                                	logger.error(e.getMessage());
                                }
                                // 如果程序已经在停止了，就不参与竞选了
                                if (!destory) {
                                    logger.info("休眠10秒之后节点再次开始竞选Leader...");
                                    selector.requeue();
                                }
                            }
                        }
                    });
                    t.setDaemon(true);
                    t.start();
                    logger.warn("当前节点放弃领导权");
                }

                @Override
                public void stateChanged(CuratorFramework client, ConnectionState newState) {
                    logger.warn("ZK连接状态改变：{}", newState);
                    if (client.getConnectionStateErrorPolicy().isErrorState(newState)) {
                        if (hasLeadership()) {
                            logger.error("连接丢失，放弃Leader");
                            relinquished();
                        }
                    }
                }
            };
            selector = new LeaderSelector(curatorClient, zkClientInfo.getLeaderPath(), listener);
            selector.start();
        } catch (Exception e) {
            logger.error("启动异常，程序退出！", e);
            System.exit(1);
        }
    }

    @Override
    public boolean isConnected() {
        return applicationRunner.isConnected();
    }

    @Override
    public void disconnect() {
        applicationRunner.disconnect();
    }

    @Override
    public void connect() {
        applicationRunner.connect();
    }

    @Override
    public void destory() {
        destory = true;
        applicationRunner.destory();
    }

}