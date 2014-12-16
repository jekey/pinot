package com.linkedin.pinot.transport.pool;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.PatternLayout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.AssertJUnit;
import org.testng.annotations.Test;

import com.google.common.util.concurrent.MoreExecutors;
import com.linkedin.pinot.transport.common.AsyncResponseFuture;
import com.linkedin.pinot.transport.common.KeyedFuture;
import com.linkedin.pinot.transport.common.NoneType;
import com.linkedin.pinot.transport.metrics.AggregatedPoolStats;


public class TestKeyedPoolImpl {

  protected static Logger LOG = LoggerFactory.getLogger(TestKeyedPoolImpl.class);

  static {
    org.apache.log4j.Logger.getRootLogger().addAppender(
        new ConsoleAppender(new PatternLayout(PatternLayout.TTCC_CONVERSION_PATTERN), "System.out"));
  }

  @Test
  public void testCancelAfterCheckingOut() throws Exception {
    ScheduledExecutorService timedExecutor = new ScheduledThreadPoolExecutor(1);
    ExecutorService service = new ThreadPoolExecutor(1, 1, 1, TimeUnit.DAYS, new LinkedBlockingDeque<Runnable>());
    int numKeys = 1;
    int numResourcesPerKey = 1;
    Map<String, List<String>> resources = buildCreateMap(numKeys, numResourcesPerKey);
    BlockingTestResourceManager rm = new BlockingTestResourceManager(resources, null, null, null);

    KeyedPool<String, String> kPool =
        new KeyedPoolImpl<String, String>(0, 1, 1000L, 1000 * 60 * 60, rm, timedExecutor, service, null);

    kPool.start();
    AsyncResponseFuture<String, String> f = (AsyncResponseFuture<String, String>) kPool.checkoutObject(getKey(0));
    boolean isTimedout = false;
    try {
      f.get(2, TimeUnit.SECONDS);
    } catch (TimeoutException e) {
      isTimedout = true;
    }
    AssertJUnit.assertTrue(isTimedout);
    boolean cancelled = f.cancel(false);
    AssertJUnit.assertTrue(cancelled);
    AssertJUnit.assertTrue(f.isCancelled());
    AssertJUnit.assertTrue(f.isDone());

    rm.getCreateBlockLatch().countDown();
    kPool.shutdown().get();
  }

  @Test
  public void testInvalidCheckinDestroy() throws Exception {
    ScheduledExecutorService timedExecutor = new ScheduledThreadPoolExecutor(1);
    ExecutorService service = new ThreadPoolExecutor(1, 1, 1, TimeUnit.DAYS, new LinkedBlockingDeque<Runnable>());
    int numKeys = 1;
    int numResourcesPerKey = 1;
    Map<String, List<String>> resources = buildCreateMap(numKeys, numResourcesPerKey);
    TestResourceManager rm = new TestResourceManager(resources, null, null, null);

    KeyedPool<String, String> kPool =
        new KeyedPoolImpl<String, String>(0, 1, 1000L, 1000 * 60 * 60, rm, timedExecutor, service, null);

    kPool.start();
    AsyncResponseFuture<String, String> f = (AsyncResponseFuture<String, String>) kPool.checkoutObject(getKey(0));
    String s1 = f.getOne();

    // checkin with invalid key
    boolean isException = false;
    try {
      kPool.checkinObject(getKey(1), s1);
    } catch (IllegalStateException e) {
      isException = true;
    }
    AssertJUnit.assertTrue(isException);

    // destroy with invalid key
    isException = false;
    try {
      kPool.destroyObject(getKey(1), s1);
    } catch (IllegalStateException e) {
      isException = true;
    }
    AssertJUnit.assertTrue(isException);
  }

  @Test
  public void testShutdownWhileCheckingOut() throws Exception {
    ScheduledExecutorService timedExecutor = new ScheduledThreadPoolExecutor(1);
    ExecutorService service = new ThreadPoolExecutor(1, 1, 1, TimeUnit.DAYS, new LinkedBlockingDeque<Runnable>());
    int numKeys = 1;
    int numResourcesPerKey = 1;
    Map<String, List<String>> resources = buildCreateMap(numKeys, numResourcesPerKey);
    BlockingTestResourceManager rm = new BlockingTestResourceManager(resources, null, null, null);

    KeyedPool<String, String> kPool =
        new KeyedPoolImpl<String, String>(0, 1, 1000L, 1000 * 60 * 60, rm, timedExecutor, service, null);

    kPool.start();
    AsyncResponseFuture<String, String> f = (AsyncResponseFuture<String, String>) kPool.checkoutObject(getKey(0));
    boolean isTimedout = false;
    try {
      f.get(2, TimeUnit.SECONDS);
    } catch (TimeoutException e) {
      isTimedout = true;
    }
    AssertJUnit.assertTrue(isTimedout);
    kPool.shutdown().get();

    // Future should have been done with error
    AssertJUnit.assertNull(f.get());
    AssertJUnit.assertNotNull(f.getError());
    boolean cancelled = f.cancel(false);
    AssertJUnit.assertFalse(cancelled);
    AssertJUnit.assertFalse(f.isCancelled());
    AssertJUnit.assertTrue(f.isDone());

    rm.getCreateBlockLatch().countDown();
    Thread.sleep(5000);
  }

  @Test
  public void testCreateError() throws Exception {
    ScheduledExecutorService timedExecutor = new ScheduledThreadPoolExecutor(1);
    ExecutorService service = MoreExecutors.sameThreadExecutor();
    int numKeys = 1;
    int numResourcesPerKey = 1;
    Map<String, List<String>> resources = buildCreateMap(numKeys, numResourcesPerKey);

    TestResourceManager rm = new TestResourceManager(resources, resources, null, null);

    KeyedPool<String, String> kPool =
        new KeyedPoolImpl<String, String>(0, 1, 1000L, 1000 * 60 * 60, rm, timedExecutor, service, null);
    AsyncResponseFuture<String, String> f = (AsyncResponseFuture<String, String>) kPool.checkoutObject(getKey(0));
    AssertJUnit.assertTrue(f.isDone());
    AssertJUnit.assertNull(f.get());
    AssertJUnit.assertNotNull(f.getError());

    kPool.shutdown().get();
    AggregatedPoolStats s = (AggregatedPoolStats) kPool.getStats();
    s.refresh();
    AssertJUnit.assertEquals(1, s.getTotalCreateErrors());
  }

  @Test
  public void testDestroyError() throws Exception {
    ScheduledExecutorService timedExecutor = new ScheduledThreadPoolExecutor(1);
    ExecutorService service = MoreExecutors.sameThreadExecutor();
    int numKeys = 1;
    int numResourcesPerKey = 1;
    Map<String, List<String>> resources = buildCreateMap(numKeys, numResourcesPerKey);

    TestResourceManager rm = new TestResourceManager(resources, null, resources, null);

    KeyedPool<String, String> kPool =
        new KeyedPoolImpl<String, String>(0, 5, 1000L, 1000 * 60 * 60, rm, timedExecutor, service, null);
    AsyncResponseFuture<String, String> f = (AsyncResponseFuture<String, String>) kPool.checkoutObject(getKey(0));
    String r = f.getOne();
    AssertJUnit.assertTrue(f.isDone());
    AssertJUnit.assertNull(f.getError());

    // Create a countdown latch that waits for the attempt to delete the resource
    CountDownLatch latch = new CountDownLatch(1);
    rm.setCountDownLatch(latch);
    kPool.destroyObject(getKey(0), r);
    latch.await();

    // shutdown
    kPool.shutdown().get();

    AggregatedPoolStats s = (AggregatedPoolStats) kPool.getStats();
    s.refresh();
    AssertJUnit.assertEquals(1, s.getTotalDestroyErrors());
  }

  @Test
  /**
   * IdleTimeout = 1sec
   * Pool => 5 keys. 1 resource per key ( 0 min, 5 max).
   * 
   * 1. Checkout and checkin object to ensure they are created
   * 2. Wait for destroy latch to ensure the objects are deleted after they timeout
   * 3. Verify metrics
   * 4. Ensure shutdown succeeds
   * 
   * @throws Exception
   */
  public void testTimeout() throws Exception {
    ScheduledExecutorService timedExecutor = new ScheduledThreadPoolExecutor(1);
    ExecutorService service = MoreExecutors.sameThreadExecutor();
    int numKeys = 5;
    int numResourcesPerKey = 1;
    TestResourceManager rm = new TestResourceManager(buildCreateMap(numKeys, numResourcesPerKey), null, null, null);

    // Idle Timeout 1 second
    KeyedPool<String, String> kPool =
        new KeyedPoolImpl<String, String>(0, 5, 1000L, 100, rm, timedExecutor, service, null);

    // Create a countdown latch that waits for all resources to be deleted
    CountDownLatch latch = new CountDownLatch(numKeys * numResourcesPerKey);
    rm.setCountDownLatch(latch);

    kPool.start();
    AggregatedPoolStats s = (AggregatedPoolStats) kPool.getStats();

    // checkout and checkin back all
    for (int j = 0; j < numResourcesPerKey; j++) {
      for (int i = 0; i < numKeys; i++) {
        KeyedFuture<String, String> rFuture = kPool.checkoutObject(getKey(i));
        String resource = rFuture.getOne();
      }
    }

    // checkin back all
    for (int j = 0; j < numResourcesPerKey; j++) {
      for (int i = 0; i < numKeys; i++) {
        kPool.checkinObject(getKey(i), getResource(i, j));
      }
    }

    //Wait for all to be destroyed
    latch.await();
    s.refresh();
    AssertJUnit.assertEquals(5, s.getTotalTimedOut());

    //Verify all objects are destroyed
    Map<String, List<String>> destroyedMap = rm.getDestroyedMap();

    AssertJUnit.assertEquals(numKeys, destroyedMap.keySet().size());
    for (int i = 0; i < numKeys; i++) {
      List<String> r = destroyedMap.get(getKey(i));
      AssertJUnit.assertEquals("Resource for Key (" + getKey(i) + ")", numResourcesPerKey, r.size());
      for (int j = 0; j < numResourcesPerKey; j++) {
        AssertJUnit.assertTrue(r.contains(getResource(i, j)));
      }
    }

    // Proper shutdown
    Future<Map<String, NoneType>> f = kPool.shutdown();
    f.get();
  }

  @Test
  /**
   * Pool contains 5 inner pools with 5 resources as max capacity
   * Checkout all of them. Verify checked out is expected. Verify aggregated stats
   * Checkin all of them. At each step check stats
   * Checkout one from each inner pool and destroy them. Check stats
   * Shutdown. Ensure clean shutdown.
   * @throws Exception
   */
  public void testPoolImpl1() throws Exception {
    ScheduledExecutorService timedExecutor = new ScheduledThreadPoolExecutor(1);
    ExecutorService service = MoreExecutors.sameThreadExecutor();
    int numKeys = 5;
    int numResourcesPerKey = 5;
    TestResourceManager rm = new TestResourceManager(buildCreateMap(numKeys, numResourcesPerKey), null, null, null);
    KeyedPool<String, String> kPool =
        new KeyedPoolImpl<String, String>(5, 5, 1000 * 60 * 60L, 100, rm, timedExecutor, service, null);

    kPool.start();
    AggregatedPoolStats s = (AggregatedPoolStats) kPool.getStats();

    int c = 1;
    for (int j = 0; j < numResourcesPerKey; j++) {
      for (int i = 0; i < numKeys; i++) {
        KeyedFuture<String, String> rFuture = kPool.checkoutObject(getKey(i));
        String resource = rFuture.getOne();
        AssertJUnit.assertEquals(getResource(i, j), resource);
        s.refresh();
        AssertJUnit.assertEquals(c++, s.getCheckedOut());
      }
    }

    s = (AggregatedPoolStats) kPool.getStats();
    AssertJUnit.assertEquals(numKeys * numResourcesPerKey, s.getTotalCreated());
    int checkedOut = c - 1;
    // checkin back all
    for (int j = 0; j < numResourcesPerKey; j++) {
      for (int i = 0; i < numKeys; i++) {
        kPool.checkinObject(getKey(i), getResource(i, j));
        s.refresh();
        AssertJUnit.assertEquals(--checkedOut, s.getCheckedOut());
      }
    }

    s = (AggregatedPoolStats) kPool.getStats();
    // checkout one from each and destroy them
    c = 1;
    int d = 1;
    for (int i = 0; i < numKeys; i++) {
      KeyedFuture<String, String> rFuture = kPool.checkoutObject(getKey(i));
      String resource = rFuture.getOne();
      AssertJUnit.assertEquals(getResource(i, 0), resource);
      CountDownLatch latch = new CountDownLatch(1);
      rm.setCountDownLatch(latch);
      kPool.destroyObject(getKey(i), resource);
      latch.await();
      Thread.sleep(1000);
      s.refresh();
      AssertJUnit.assertEquals(0, s.getCheckedOut());
      AssertJUnit.assertEquals(d++, s.getTotalDestroyed());
    }
    Future<Map<String, NoneType>> f = kPool.shutdown();
    f.get();
    //Verify all objects are destroyed
    Map<String, List<String>> destroyedMap = rm.getDestroyedMap();

    AssertJUnit.assertEquals(numKeys, destroyedMap.keySet().size());
    for (int i = 0; i < numKeys; i++) {
      List<String> r = destroyedMap.get(getKey(i));
      AssertJUnit.assertEquals("Resource for Key (" + getKey(i) + ")", numResourcesPerKey, r.size());
      for (int j = 0; j < numResourcesPerKey; j++) {
        AssertJUnit.assertTrue(r.contains(getResource(i, j)));
      }
    }
  }

  @Test
  /**
   * Pool contains 5 inner pools with 5 resources as max capacity
   * First checkout and checkin all resources
   * Checkout one from each inner pool.
   * Shutdown now. This should not complete.
   * Destroy the checked out objects. Check stats
   * shutdown should have happened
   * @throws Exception
   */
  public void testShutdown() throws Exception {
    ScheduledExecutorService timedExecutor = new ScheduledThreadPoolExecutor(1);
    ExecutorService service = MoreExecutors.sameThreadExecutor();
    int numKeys = 5;
    int numResourcesPerKey = 5;
    TestResourceManager rm = new TestResourceManager(buildCreateMap(numKeys, numResourcesPerKey), null, null, null);
    KeyedPool<String, String> kPool =
        new KeyedPoolImpl<String, String>(5, 5, 1000 * 60 * 60L, 100, rm, timedExecutor, service, null);

    kPool.start();
    AggregatedPoolStats s = (AggregatedPoolStats) kPool.getStats();
    int c = 1;
    for (int j = 0; j < numResourcesPerKey; j++) {
      for (int i = 0; i < numKeys; i++) {
        KeyedFuture<String, String> rFuture = kPool.checkoutObject(getKey(i));
        String resource = rFuture.getOne();
        AssertJUnit.assertEquals(getResource(i, j), resource);
        s.refresh();
        AssertJUnit.assertEquals(c++, s.getCheckedOut());
      }
    }

    s = (AggregatedPoolStats) kPool.getStats();
    AssertJUnit.assertEquals(numKeys * numResourcesPerKey, s.getTotalCreated());
    int checkedOut = c - 1;
    // checkin back all
    for (int j = 0; j < numResourcesPerKey; j++) {
      for (int i = 0; i < numKeys; i++) {
        kPool.checkinObject(getKey(i), getResource(i, j));
        s.refresh();
        AssertJUnit.assertEquals(--checkedOut, s.getCheckedOut());
      }
    }

    // Check out 1 object for each key
    c = 1;
    for (int i = 0; i < numKeys; i++) {
      KeyedFuture<String, String> rFuture = kPool.checkoutObject(getKey(i));
      String resource = rFuture.getOne();
      AssertJUnit.assertEquals(getResource(i, 0), resource);
      s.refresh();
      AssertJUnit.assertEquals(c, s.getCheckedOut());
      c++;
    }
    AssertJUnit.assertEquals(numKeys * numResourcesPerKey, s.getPoolSize());
    AssertJUnit.assertEquals((numKeys * numResourcesPerKey) - 5, s.getIdleCount());

    // SHutdown but it should not be done.
    Future<Map<String, NoneType>> f = kPool.shutdown();
    FutureReader<Map<String, NoneType>> reader = new FutureReader<Map<String, NoneType>>(f);
    reader.start();
    reader.getBeginLatch().await();
    AssertJUnit.assertTrue(reader.isStarted());
    AssertJUnit.assertFalse(reader.isDone());
    //none are destroyed
    AssertJUnit.assertEquals(0, rm.getDestroyedMap().keySet().size());

    // Now destroy some and checkin others
    int d = 0;
    for (int i = 0; i < numKeys; i++) {
      if ((i % 2) == 0) {
        kPool.destroyObject(getKey(i), getResource(i, 0));
        s.refresh();
        AssertJUnit.assertEquals(++d, s.getTotalDestroyed());
      } else {
        kPool.checkinObject(getKey(i), getResource(i, 0));
      }
    }
    s.refresh();
    AssertJUnit.assertEquals(3, s.getTotalDestroyed());

    // Now shutdown should complete
    f.get();
    reader.getEndLatch().await();
    AssertJUnit.assertTrue(reader.isDone());

    // Do one more shutdown call
    Future<Map<String, NoneType>> f2 = kPool.shutdown();
    f2.get();
    //Verify all objects are destroyed
    Map<String, List<String>> destroyedMap = rm.getDestroyedMap();

    AssertJUnit.assertEquals(numKeys, destroyedMap.keySet().size());
    for (int i = 0; i < numKeys; i++) {
      List<String> r = destroyedMap.get(getKey(i));
      AssertJUnit.assertEquals("Resource for Key (" + getKey(i) + ")", numResourcesPerKey, r.size());
      for (int j = 0; j < numResourcesPerKey; j++) {
        AssertJUnit.assertTrue(r.contains(getResource(i, j)));
      }
    }
  }

  private Map<String, List<String>> buildCreateMap(int numKeys, int numResourcesPerKey) {
    Map<String, List<String>> createdMap = new HashMap<String, List<String>>();
    for (int i = 0; i < numKeys; i++) {
      String key = getKey(i);
      List<String> list = new ArrayList<String>();
      for (int j = 0; j < numKeys; j++) {
        list.add(getResource(i, j));
      }
      createdMap.put(key, list);
    }
    return createdMap;
  }

  private String getKey(int id) {
    return "key_" + id;
  }

  private String getResource(int key, int resource) {
    String k = getKey(key);
    return k + "_resource_" + resource;
  }

  public static class BlockingTestResourceManager extends TestResourceManager {
    // Latch to block creating resource
    private final CountDownLatch _createBlockLatch = new CountDownLatch(1);

    public BlockingTestResourceManager(Map<String, List<String>> createdMap,
        Map<String, List<String>> failCreateResources, Map<String, List<String>> failDestroyResources,
        Map<String, List<String>> failValidationResources) {
      super(createdMap, failCreateResources, failDestroyResources, failValidationResources);
    }

    @Override
    public String create(String key) {
      try {
        _createBlockLatch.await();
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
      LOG.info("Create Latch opened. Proceding with creating resource !!");
      return super.create(key);
    }

    public CountDownLatch getCreateBlockLatch() {
      return _createBlockLatch;
    }

  }

  public static class TestResourceManager implements PooledResourceManager<String, String> {
    // input related
    public Map<String, List<String>> _createdMap = new HashMap<String, List<String>>();
    public Map<String, List<String>> _failCreateResources = new HashMap<String, List<String>>();
    public Map<String, List<String>> _failDestroyResources = new HashMap<String, List<String>>();
    public Map<String, List<String>> _failValidationResources = new HashMap<String, List<String>>();

    // output related
    public Map<String, Integer> _createdIndex = new HashMap<String, Integer>();
    public Map<String, List<String>> _destroyedMap = new HashMap<String, List<String>>();
    public Map<String, List<String>> _validatedMap = new HashMap<String, List<String>>();

    private CountDownLatch _latch = null;

    public void setCountDownLatch(CountDownLatch latch) {
      _latch = latch;
    }

    public TestResourceManager(Map<String, List<String>> createdMap, Map<String, List<String>> failCreateResources,
        Map<String, List<String>> failDestroyResources, Map<String, List<String>> failValidationResources) {
      if (null != createdMap) {
        _createdMap.putAll(createdMap);
      }

      if (null != failCreateResources) {
        _failCreateResources.putAll(failCreateResources);
      }

      if (null != failDestroyResources) {
        _failDestroyResources.putAll(failDestroyResources);
      }

      if (null != failValidationResources) {
        _failValidationResources.putAll(failValidationResources);
      }
    }

    @Override
    public String create(String key) {

      Integer index = _createdIndex.get(key);

      if (null == index) {
        index = 0;
      }

      List<String> failCreateList = _failCreateResources.get(key);

      if ((null != failCreateList) && failCreateList.contains(_createdMap.get(key).get(index))) {
        return null;
      }
      _createdIndex.put(key, index + 1);
      return _createdMap.get(key).get(index);
    }

    @Override
    public boolean destroy(String key, boolean isBad, String resource) {
      boolean fail = false;
      List<String> fails = _failDestroyResources.get(key);
      if (null != fails) {
        fail = fails.contains(resource);
      }
      List<String> destroyed = _destroyedMap.get(key);
      if (null == destroyed) {
        destroyed = new ArrayList<String>();
        _destroyedMap.put(key, destroyed);
      }
      destroyed.add(resource);
      if (null != _latch) {
        _latch.countDown();
      }
      return !fail;
    }

    @Override
    public boolean validate(String key, String resource) {
      boolean fail = false;
      List<String> fails = _failValidationResources.get(key);
      if (null != fails) {
        fail = fails.contains(resource);
      }
      List<String> validated = _validatedMap.get(key);
      if (null == validated) {
        validated = new ArrayList<String>();
        _validatedMap.put(key, validated);
      }
      validated.add(resource);
      return !fail;
    }

    public Map<String, Integer> getCreatedIndex() {
      return _createdIndex;
    }

    public Map<String, List<String>> getDestroyedMap() {
      return _destroyedMap;
    }

    public Map<String, List<String>> getValidatedMap() {
      return _validatedMap;
    }
  }

  public static class FutureReader<T> extends Thread {
    private boolean _started = false;
    private final Future<T> _future;
    private boolean _done = false;

    private final CountDownLatch _latch = new CountDownLatch(1);
    private final CountDownLatch _latch2 = new CountDownLatch(1);

    public FutureReader(Future<T> future) {
      _future = future;
    }

    @Override
    public void run() {
      _started = true;
      _latch.countDown();
      try {
        _future.get();
        _done = true;
      } catch (InterruptedException e) {
        // TODO Auto-generated catch block
        e.printStackTrace();
      } catch (ExecutionException e) {
        // TODO Auto-generated catch block
        e.printStackTrace();
      }
      _latch2.countDown();
    }

    public boolean isStarted() {
      return _started;
    }

    public boolean isDone() {
      return _done;
    }

    public CountDownLatch getBeginLatch() {
      return _latch;
    }

    public CountDownLatch getEndLatch() {
      return _latch2;
    }
  }
}