package org.rojo.util;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

/**
 * lrucache
 *
 * @author solr
 */
public class LruCache implements Cache
{

  private final ConcurrentHashMap<Object, CacheEntry> map;
  private final Map<Class, Set<String>> keys;
  private final int upperWaterMark, lowerWaterMark;
  private final ReentrantLock markAndSweepLock = new ReentrantLock(true);
  private boolean isCleaning = false;  // not volatile... piggybacked on other volatile vars
  private final boolean newThreadForCleanup;
  private final Stats stats = new Stats();
  private final int acceptableWaterMark;
  private long oldestEntry = 0;  // not volatile, only accessed in the cleaning method
  private CacheoutListerner evictionListener;
  private CleanupThread cleanupThread;

  public LruCache(int upperWaterMark, final int lowerWaterMark, int acceptableWatermark,
          int initialSize, boolean runCleanupThread, boolean runNewThreadForCleanup,
          CacheoutListerner evictionListener)
  {
    if (upperWaterMark < 1)
    {
      throw new IllegalArgumentException("upperWaterMark must be > 0");
    }
    if (lowerWaterMark >= upperWaterMark)
    {
      throw new IllegalArgumentException("lowerWaterMark must be  < upperWaterMark");
    }
    map = new ConcurrentHashMap<>(initialSize);
    keys = new HashMap<>();
    newThreadForCleanup = runNewThreadForCleanup;
    this.upperWaterMark = upperWaterMark;
    this.lowerWaterMark = lowerWaterMark;
    this.acceptableWaterMark = acceptableWatermark;
    this.evictionListener = evictionListener;
    if (runCleanupThread)
    {
      cleanupThread = new CleanupThread(this);
      cleanupThread.setName("LruCache cleanup");
      cleanupThread.start();
    }
  }

  public LruCache(int size, int lowerWatermark)
  {
    this(size, lowerWatermark, (int) Math.floor((lowerWatermark + size) / 2),
            (int) Math.ceil(0.75 * size), true, false, null);
  }

  /**
   * lru
   *
   * @param size
   */
  public LruCache(int size)
  {
    this(size, (int) Math.floor(0.9 * size), (int) Math.floor(0.95 * size),
            (int) Math.ceil(0.75 * size), true, false, null);
  }

  private Object get(String key)
  {
    CacheEntry e = map.get(key);
    if (e == null)
    {
      stats.missCounter.incrementAndGet();
      return null;
    }
    e.lastAccessed = stats.accessCounter.incrementAndGet();
    return e.value;
  }

  private Object put(String key, Object val)
  {
    if (val == null)
    {
      return null;
    }
    CacheEntry e = new CacheEntry<>(key, val, stats.accessCounter.incrementAndGet());
    CacheEntry oldCacheEntry = map.put(key, e);
    int currentSize;
    if (oldCacheEntry == null)
    {
      currentSize = stats.size.incrementAndGet();
    } else
    {
      currentSize = stats.size.get();
    }
    stats.putCounter.incrementAndGet();
    // Check if we need to clear out old entries from the cache.
    // isCleaning variable is checked instead of markAndSweepLock.isLocked()
    // for performance because every put invokation will check until
    // the size is back to an acceptable level.
    //
    // There is a race between the check and the call to markAndSweep, but
    // it's unimportant because markAndSweep actually aquires the lock or returns if it can't.
    //
    // Thread safety note: isCleaning read is piggybacked (comes after) other volatile reads
    // in this method.
    if (currentSize > upperWaterMark && !isCleaning)
    {
      if (newThreadForCleanup)
      {
        new Thread()
        {
          @Override
          public void run()
          {
            markAndSweep();
          }
        }.start();
      } else if (cleanupThread != null)
      {
        cleanupThread.wakeThread();
      } else
      {
        markAndSweep();
      }
    }
    return oldCacheEntry == null ? null : oldCacheEntry.value;
  }

  /**
   * Removes items from the cache to bring the size down to an acceptable value
   * ('acceptableWaterMark').
   * <p/>
   * It is done in two stages. In the first stage, least recently used items are
   * evicted. If, after the first stage, the cache size is still greater than
   * 'acceptableSize' config parameter, the second stage takes over.
   * <p/>
   * The second stage is more intensive and tries to bring down the cache size
   * to the 'lowerWaterMark' config parameter.
   */
  private void markAndSweep()
  {
    // if we want to keep at least 1000 entries, then timestamps of
    // current through current-1000 are guaranteed not to be the oldest (but that does
    // not mean there are 1000 entries in that group... it's acutally anywhere between
    // 1 and 1000).
    // Also, if we want to remove 500 entries, then
    // oldestEntry through oldestEntry+500 are guaranteed to be
    // removed (however many there are there).

    if (!markAndSweepLock.tryLock())
    {
      return;
    }
    try
    {
      long oldestEntry = this.oldestEntry;
      isCleaning = true;
      this.oldestEntry = oldestEntry;     // volatile write to make isCleaning visible

      long timeCurrent = stats.accessCounter.get();
      int sz = stats.size.get();

      int numRemoved = 0;
      int numKept = 0;
      long newestEntry = timeCurrent;
      long newNewestEntry = -1;
      long newOldestEntry = Long.MAX_VALUE;

      int wantToKeep = lowerWaterMark;
      int wantToRemove = sz - lowerWaterMark;

      @SuppressWarnings("unchecked") // generic array's are anoying
      CacheEntry[] eset = new CacheEntry[sz];
      int eSize = 0;

      // System.out.println("newestEntry="+newestEntry + " oldestEntry="+oldestEntry);
      // System.out.println("items removed:" + numRemoved + " numKept=" + numKept + " esetSz="+ eSize + " sz-numRemoved=" + (sz-numRemoved));
      for (CacheEntry ce : map.values())
      {
        // set lastAccessedCopy to avoid more volatile reads
        ce.lastAccessedCopy = ce.lastAccessed;
        long thisEntry = ce.lastAccessedCopy;

        // since the wantToKeep group is likely to be bigger than wantToRemove, check it first
        if (thisEntry > newestEntry - wantToKeep)
        {
          // this entry is guaranteed not to be in the bottom
          // group, so do nothing.
          numKept++;
          newOldestEntry = Math.min(thisEntry, newOldestEntry);
        } else if (thisEntry < oldestEntry + wantToRemove)
        { // entry in bottom group?
          // this entry is guaranteed to be in the bottom group
          // so immediately remove it from the map.
          evictEntry((String) ce.key);
          numRemoved++;
        } else // This entry *could* be in the bottom group.
        // Collect these entries to avoid another full pass... this is wasted
        // effort if enough entries are normally removed in this first pass.
        // An alternate impl could make a full second pass.
        {
          if (eSize < eset.length - 1)
          {
            eset[eSize++] = ce;
            newNewestEntry = Math.max(thisEntry, newNewestEntry);
            newOldestEntry = Math.min(thisEntry, newOldestEntry);
          }
        }
      }

      // System.out.println("items removed:" + numRemoved + " numKept=" + numKept + " esetSz="+ eSize + " sz-numRemoved=" + (sz-numRemoved));
      // TODO: allow this to be customized in the constructor?
      int numPasses = 1; // maximum number of linear passes over the data

      // if we didn't remove enough entries, then make more passes
      // over the values we collected, with updated min and max values.
      while (sz - numRemoved > acceptableWaterMark && --numPasses >= 0)
      {
        oldestEntry = newOldestEntry == Long.MAX_VALUE ? oldestEntry : newOldestEntry;
        newOldestEntry = Long.MAX_VALUE;
        newestEntry = newNewestEntry;
        newNewestEntry = -1;
        wantToKeep = lowerWaterMark - numKept;
        wantToRemove = sz - lowerWaterMark - numRemoved;
        // iterate backward to make it easy to remove items.
        for (int i = eSize - 1; i >= 0; i--)
        {
          CacheEntry ce = eset[i];
          long thisEntry = ce.lastAccessedCopy;
          if (thisEntry > newestEntry - wantToKeep)
          {
            // this entry is guaranteed not to be in the bottom
            // group, so do nothing but remove it from the eset.
            numKept++;
            // remove the entry by moving the last element to it's position
            eset[i] = eset[eSize - 1];
            eSize--;
            newOldestEntry = Math.min(thisEntry, newOldestEntry);
          } else if (thisEntry < oldestEntry + wantToRemove)
          { // entry in bottom group?
            // this entry is guaranteed to be in the bottom group
            // so immediately remove it from the map.
            evictEntry((String) ce.key);
            numRemoved++;
            // remove the entry by moving the last element to it's position
            eset[i] = eset[eSize - 1];
            eSize--;
          } else
          {
            // This entry *could* be in the bottom group, so keep it in the eset,
            // and update the stats.
            newNewestEntry = Math.max(thisEntry, newNewestEntry);
            newOldestEntry = Math.min(thisEntry, newOldestEntry);
          }
        }
        // System.out.println("items removed:" + numRemoved + " numKept=" + numKept + " esetSz="+ eSize + " sz-numRemoved=" + (sz-numRemoved));
      }
      // if we still didn't remove enough entries, then make another pass while
      // inserting into a priority queue
      if (sz - numRemoved > acceptableWaterMark)
      {
        oldestEntry = newOldestEntry == Long.MAX_VALUE ? oldestEntry : newOldestEntry;
        newOldestEntry = Long.MAX_VALUE;
        newestEntry = newNewestEntry;
        newNewestEntry = -1;
        wantToKeep = lowerWaterMark - numKept;
        wantToRemove = sz - lowerWaterMark - numRemoved;
        PQueue queue = new PQueue<>(wantToRemove);
        for (int i = eSize - 1; i >= 0; i--)
        {
          CacheEntry ce = eset[i];
          long thisEntry = ce.lastAccessedCopy;
          if (thisEntry > newestEntry - wantToKeep)
          {
            // this entry is guaranteed not to be in the bottom
            // group, so do nothing but remove it from the eset.
            numKept++;
            // removal not necessary on last pass.
            // eset[i] = eset[eSize-1];
            // eSize--;

            newOldestEntry = Math.min(thisEntry, newOldestEntry);

          } else if (thisEntry < oldestEntry + wantToRemove)
          {  // entry in bottom group?
            // this entry is guaranteed to be in the bottom group
            // so immediately remove it.
            evictEntry((String) ce.key);
            numRemoved++;

            // removal not necessary on last pass.
            // eset[i] = eset[eSize-1];
            // eSize--;
          } else
          {
            // This entry *could* be in the bottom group.
            // add it to the priority queue

            // everything in the priority queue will be removed, so keep track of
            // the lowest value that ever comes back out of the queue.
            // first reduce the size of the priority queue to account for
            // the number of items we have already removed while executing
            // this loop so far.
            queue.myMaxSize = sz - lowerWaterMark - numRemoved;
            while (queue.size() > queue.myMaxSize && queue.size() > 0)
            {
              CacheEntry otherEntry = (CacheEntry) queue.pop();
              newOldestEntry = Math.min(otherEntry.lastAccessedCopy, newOldestEntry);
            }
            if (queue.myMaxSize <= 0)
            {
              break;
            }
            Object o = queue.myInsertWithOverflow(ce);
            if (o != null)
            {
              newOldestEntry = Math.min(((CacheEntry) o).lastAccessedCopy, newOldestEntry);
            }
          }
        }
        // Now delete everything in the priority queue.
        // avoid using pop() since order doesn't matter anymore
        for (Object ce : queue.getValues())
        {
          if (ce == null)
          {
            continue;
          }
          evictEntry((String) ((CacheEntry) ce).key);
          numRemoved++;
        }
        // System.out.println("items removed:" + numRemoved + " numKept=" + numKept + " initialQueueSize="+ wantToRemove + " finalQueueSize=" + queue.size() + " sz-numRemoved=" + (sz-numRemoved));
      }
      oldestEntry = newOldestEntry == Long.MAX_VALUE ? oldestEntry : newOldestEntry;
      this.oldestEntry = oldestEntry;
    } finally
    {
      isCleaning = false;  // set before markAndSweep.unlock() for visibility
      markAndSweepLock.unlock();
    }
  }

  @Override
  public void cache(Object entity, String id)
  {
    String key = entity.getClass().getName() + ":" + id;
    this.put(key, entity);
    Set<String> set = keys.get(entity.getClass());
    if (set == null)
    {
      set = new HashSet<>();
      keys.put(entity.getClass(), set);
    }
    set.add(key);
  }

  @Override
  public <T> T get(Class<T> claz, String id)
  {
    return (T) this.get(claz.getName() + ":" + id);
  }

  @Override
  public void evict(Class claz, String id)
  {
    this.evictEntry(claz.getName() + ":" + id);
  }

  @Override
  public void evict(Class claz)
  {
    Set<String> set = keys.remove(claz);
    for (String k : set)
    {
      this.evictEntry(k);
    }
  }

  @Override
  public void setCacheoutListerner(CacheoutListerner cacheoutListerner)
  {
    this.evictionListener = cacheoutListerner;
  }

  @Override
  public CacheoutListerner getCacheoutListerner()
  {
    return this.evictionListener;
  }

  @Override
  public Stats stats()
  {
    return stats;
  }

  @Override
  public void clear()
  {
    map.clear();
  }

  private static class PQueue<K, V> extends PriorityQueue<CacheEntry<K, V>>
  {

    int myMaxSize;
    final Object[] heap;

    PQueue(int maxSz)
    {
      super(maxSz);
      heap = getHeapArray();
      myMaxSize = maxSz;
    }

    @SuppressWarnings("unchecked")
    Iterable<CacheEntry<K, V>> getValues()
    {
      return (Iterable) Collections.unmodifiableCollection(Arrays.asList(heap));
    }

    @Override
    protected boolean lessThan(CacheEntry a, CacheEntry b)
    {
      // reverse the parameter order so that the queue keeps the oldest items
      return b.lastAccessedCopy < a.lastAccessedCopy;
    }

    // necessary because maxSize is private in base class
    @SuppressWarnings("unchecked")
    public CacheEntry<K, V> myInsertWithOverflow(CacheEntry<K, V> element)
    {
      if (size() < myMaxSize)
      {
        add(element);
        return null;
      } else if (size() > 0 && !lessThan(element, (CacheEntry<K, V>) heap[1]))
      {
        CacheEntry<K, V> ret = (CacheEntry<K, V>) heap[1];
        heap[1] = element;
        updateTop();
        return ret;
      } else
      {
        return element;
      }
    }
  }

  private void evictEntry(String key)
  {
    CacheEntry o = map.remove(key);
    if (o == null)
    {
      return;
    }
    try
    {
      int index = key.indexOf(':');
      Set<String> set = keys.get(Class.forName(key.substring(0, index)));
      set.remove(key);
    } catch (Exception e)
    {
    }
    stats.size.decrementAndGet();
    stats.evictionCounter.incrementAndGet();
    if (evictionListener != null)
    {
      evictionListener.onCacheout(o.value.getClass(), key);
    }
  }

  /**
   * Returns 'n' number of oldest accessed entries present in this cache.
   *
   * This uses a TreeSet to collect the 'n' oldest items ordered by ascending
   * last access time and returns a LinkedHashMap containing 'n' or less than
   * 'n' entries.
   *
   * @param n the number of oldest items needed
   * @return a LinkedHashMap containing 'n' or less than 'n' entries
   */
  public Map getOldestAccessedItems(int n)
  {
    Map result = new LinkedHashMap<>();
    if (n <= 0)
    {
      return result;
    }
    TreeSet<CacheEntry> tree = new TreeSet<>();
    markAndSweepLock.lock();
    try
    {
      for (Map.Entry<Object, CacheEntry> entry : map.entrySet())
      {
        CacheEntry ce = entry.getValue();
        ce.lastAccessedCopy = ce.lastAccessed;
        if (tree.size() < n)
        {
          tree.add(ce);
        } else if (ce.lastAccessedCopy < tree.first().lastAccessedCopy)
        {
          tree.remove(tree.first());
          tree.add(ce);
        }
      }
    } finally
    {
      markAndSweepLock.unlock();
    }
    for (CacheEntry e : tree)
    {
      result.put(e.key, e.value);
    }
    return result;
  }

  public Map getLatestAccessedItems(int n)
  {
    Map result = new LinkedHashMap<>();
    if (n <= 0)
    {
      return result;
    }
    TreeSet<CacheEntry> tree = new TreeSet<>();
    // we need to grab the lock since we are changing lastAccessedCopy
    markAndSweepLock.lock();
    try
    {
      for (Map.Entry<Object, CacheEntry> entry : map.entrySet())
      {
        CacheEntry ce = entry.getValue();
        ce.lastAccessedCopy = ce.lastAccessed;
        if (tree.size() < n)
        {
          tree.add(ce);
        } else if (ce.lastAccessedCopy > tree.last().lastAccessedCopy)
        {
          tree.remove(tree.last());
          tree.add(ce);
        }
      }
    } finally
    {
      markAndSweepLock.unlock();
    }
    for (CacheEntry e : tree)
    {
      result.put(e.key, e.value);
    }
    return result;
  }

  public int size()
  {
    return stats.size.get();
  }

  public Map<Object, CacheEntry> getMap()
  {
    return map;
  }

  private static class CacheEntry<K, V> implements Comparable<CacheEntry<K, V>>
  {

    K key;
    V value;
    volatile long lastAccessed = 0;
    long lastAccessedCopy = 0;

    public CacheEntry(K key, V value, long lastAccessed)
    {
      this.key = key;
      this.value = value;
      this.lastAccessed = lastAccessed;
    }

    public void setLastAccessed(long lastAccessed)
    {
      this.lastAccessed = lastAccessed;
    }

    @Override
    public int compareTo(CacheEntry<K, V> that)
    {
      if (this.lastAccessedCopy == that.lastAccessedCopy)
      {
        return 0;
      }
      return this.lastAccessedCopy < that.lastAccessedCopy ? 1 : -1;
    }

    @Override
    public int hashCode()
    {
      return value.hashCode();
    }

    @Override
    public boolean equals(Object obj)
    {
      return value.equals(obj);
    }

    @Override
    public String toString()
    {
      return "key: " + key + " value: " + value + " lastAccessed:" + lastAccessed;
    }
  }

  private boolean isDestroyed = false;

  public void destroy()
  {
    try
    {
      if (cleanupThread != null)
      {
        cleanupThread.stopThread();
      }
    } finally
    {
      isDestroyed = true;
    }
  }

  public Stats getStats()
  {
    return stats;
  }

  public static interface EvictionListener
  {

    public void evictedEntry(String key, Object value);
  }

  private static class CleanupThread extends Thread
  {

    private final WeakReference<LruCache> cache;
    private boolean stop = false;

    public CleanupThread(LruCache c)
    {
      cache = new WeakReference<>(c);
    }

    @Override
    public void run()
    {
      while (true)
      {
        synchronized (this)
        {
          if (stop)
          {
            break;
          }
          try
          {
            this.wait();
          } catch (InterruptedException e)
          {
          }
        }
        if (stop)
        {
          break;
        }
        LruCache c = cache.get();
        if (c == null)
        {
          break;
        }
        c.markAndSweep();
      }
    }

    void wakeThread()
    {
      synchronized (this)
      {
        this.notify();
      }
    }

    void stopThread()
    {
      synchronized (this)
      {
        stop = true;
        this.notify();
      }
    }
  }

  @Override
  protected void finalize() throws Throwable
  {
    try
    {
      if (!isDestroyed)
      {
        destroy();
      }
    } finally
    {
      super.finalize();
    }
  }
}
