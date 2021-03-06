package org.shihyu.clustering.scheduler.leader;

import static java.util.stream.Collectors.toList;

import java.net.InetAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.cache.PathChildrenCache;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheEvent;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheListener;
import org.apache.curator.framework.recipes.leader.CancelLeadershipException;
import org.apache.curator.framework.recipes.leader.LeaderSelector;
import org.apache.curator.framework.recipes.leader.LeaderSelectorListener;
import org.apache.curator.framework.state.ConnectionState;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.curator.utils.CloseableUtils;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * A {@link LeaderElection} implementation that uses {@link LeaderSelector}
 * 
 * @author Matt S.Y. Ho
 */
@Slf4j
public class CuratorLeaderSelector implements LeaderElection, LeaderSelectorListener,
    PathChildrenCacheListener, InitializingBean, DisposableBean, AutoCloseable {

  private @Setter Charset charset = StandardCharsets.UTF_8;
  private @Setter String connectString;
  private @Setter int baseSleepTimeMs = 1000;
  private @Setter int maxRetries = 29; // org.apache.curator.retry.ExponentialBackoffRetry.MAX_RETRIES_LIMIT
  private @Setter String rootPath = "/election";
  private @Getter @Setter String contenderId;
  private final AtomicBoolean leader = new AtomicBoolean();
  private LeaderSelector leaderSelector;
  private CuratorFramework client;
  private PathChildrenCache cache;

  @Override
  public boolean isLeader() {
    return leader.get();
  }

  @Override
  public void afterPropertiesSet() throws Exception {
    if (connectString == null || connectString.isEmpty()) {
      throw new IllegalArgumentException("'connectString' is required");
    }
    if (rootPath == null || rootPath.isEmpty()) {
      throw new IllegalArgumentException("'rootPath' is required");
    } else if (!rootPath.startsWith("/")) {
      rootPath = "/" + rootPath;
    }
    if (contenderId == null || contenderId.isEmpty()) {
      contenderId = InetAddress.getLocalHost() + "/" + UUID.randomUUID();
      log.debug("Generating random UUID [{}] for 'contenderId'", contenderId);
    }

    start();
  }

  @Override
  public void close() throws Exception {
    CloseableUtils.closeQuietly(cache);
    CloseableUtils.closeQuietly(leaderSelector);
    CloseableUtils.closeQuietly(client);
  }

  @Override
  public void destroy() throws Exception {
    close();
  }

  private synchronized void start() throws Exception {
    client = CuratorFrameworkFactory.newClient(connectString,
        new ExponentialBackoffRetry(baseSleepTimeMs, maxRetries));
    client.start();
    try {
      client.getZookeeperClient().blockUntilConnectedOrTimedOut();
    } catch (InterruptedException e) {
      client.close();
      start();
    }

    leaderSelector = new LeaderSelector(client, rootPath, this);
    leaderSelector.autoRequeue();
    leaderSelector.setId(contenderId);
    leaderSelector.start();

    cache = new PathChildrenCache(client, rootPath, true);
    cache.start();
    cache.getListenable().addListener(this);
  }

  @Override
  public void relinquishLeadership() {
    leader.set(false);
  }

  @Override
  public void takeLeadership(CuratorFramework client) throws Exception {
    leader.set(true);
    while (isLeader()) {
    }
  }

  @Override
  public void stateChanged(CuratorFramework client, ConnectionState newState) {
    if ((newState == ConnectionState.SUSPENDED) || (newState == ConnectionState.LOST)) {
      relinquishLeadership();
      throw new CancelLeadershipException();
    }
  }

  @Override
  public String toString() {
    return this.getClass().getSimpleName() + "{" + "contenderId='" + contenderId + '\''
        + ", isLeader=" + isLeader() + '}';
  }

  @Override
  public void childEvent(CuratorFramework client, PathChildrenCacheEvent event) throws Exception {
    switch (event.getType()) {
      case CHILD_REMOVED:
        String removedId = new String(event.getData().getData(), charset);
        if (removedId.equals(contenderId)) {
          if (isLeader()) {
            relinquishLeadership();
          } else {
            close();
            start();
          }
        }
      default:
        break;
    }
  }

  @Override
  public Collection<Contender> getContenders() {
    try {
      return leaderSelector.getParticipants().stream()
          .map(p -> new Contender(p.getId(), p.isLeader())).collect(toList());
    } catch (Exception e) {
      throw new Error(e);
    }
  }

}
