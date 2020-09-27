package me.stevenkin.alohajob.server.service;

import me.stevenkin.alohajob.common.extension.ExtensionLoader;
import me.stevenkin.alohajob.server.ServerAddress;
import me.stevenkin.alohajob.server.cluster.ServerCluster;
import me.stevenkin.alohajob.server.config.AlohaJobServerProperties;
import me.stevenkin.alohajob.server.lock.LockService;
import me.stevenkin.alohajob.server.model.AppDo;
import me.stevenkin.alohajob.server.repository.AppRepository;
import org.apache.commons.lang3.StringUtils;

import java.util.Date;
import java.util.concurrent.locks.Lock;

public class ServerElectionService {
    private static final int MAX_FAIL_NUM = 3;

    private AppRepository appRepository;

    private ServerAddress serverAddress;

    private LockService lockService;

    private ServerCluster serverCluster;

    private AlohaJobServerProperties alohajobServerProperties;

    public ServerElectionService(AppRepository appRepository, ServerAddress serverAddress, AlohaJobServerProperties alohajobServerProperties, ServerCluster serverCluster) {
        this.appRepository = appRepository;
        this.serverAddress = serverAddress;
        this.alohajobServerProperties = alohajobServerProperties;
        this.lockService = ExtensionLoader.getExtensionLoader(LockService.class).getExtension(alohajobServerProperties.getLockService());
        this.serverCluster = serverCluster;
    }

    /**
     * 当current server 心跳失败后重新选举服务器
     * @param appId
     * @param currentServer
     * @param failNum
     * @return
     */
    public String electServer(Long appId, String currentServer, Integer failNum) {
        // 0.当通信已经失败的服务器又通了，就不需要重新选举了
        if (StringUtils.isNotEmpty(currentServer) && currentServer.equals(serverAddress.get()))
            return currentServer;
        AppDo appDo = appRepository.findAppById(appId);
        if (appDo == null)
            throw new RuntimeException("no found app");
        // 1.当前服务器不通，就直接进行选举，否则如果服务器没有变动，就进行失败次数检查
        if (isActive(appDo.getCurrentServer())) {
            if (!currentServer.equals(appDo.getCurrentServer()))
                return appDo.getCurrentServer();
            //机会没有用完
            if (failNum < MAX_FAIL_NUM)
                return currentServer;
        }
        // 2.真正进行选举
        String lockName = appId.toString();
        Lock lock = lockService.getLock(lockName);
        lock.lock();
        try {
            if (isActive(appDo.getCurrentServer()))
                return appDo.getCurrentServer();
            AppDo newApp = new AppDo();
            newApp.setCurrentServer(serverAddress.get());
            newApp.setUpdateTime(new Date());
            appRepository.updateApp(newApp);
            return newApp.getCurrentServer();
        } finally {
            lock.unlock();
        }
    }

    private boolean isActive(String serverAddress) {
        if (StringUtils.isEmpty(serverAddress))
            return false;
        if (serverAddress.equals(this.serverAddress.get()))
            return true;
        return serverCluster.ping(serverAddress);
    }
}