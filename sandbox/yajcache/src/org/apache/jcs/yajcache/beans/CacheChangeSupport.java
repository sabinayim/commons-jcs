/*
 * Copyright 2005 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.jcs.yajcache.beans;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.jcs.yajcache.lang.annotation.*;
import org.apache.jcs.yajcache.beans.CacheChangeEvent;
import org.apache.jcs.yajcache.beans.CacheClearEvent;
import org.apache.jcs.yajcache.beans.CachePutEvent;
import org.apache.jcs.yajcache.beans.CacheRemoveEvent;
import org.apache.jcs.yajcache.core.ICache;

/**
 *
 * @author Hanson Char
 */
@CopyRightApache
public class CacheChangeSupport<V> {
    private final @NonNullable List<ICacheChangeListener<V>> listeners 
            = new CopyOnWriteArrayList<ICacheChangeListener<V>>();
    
    private ICache<V> cache;
    
    public CacheChangeSupport(@NonNullable ICache<V> cache) {
        this.cache = cache;
    }
    public void addCacheChangeListener(@NonNullable ICacheChangeListener<V> listener)
    {
        listeners.add(listener);
    }
    public void removeCacheChangeListener(@NonNullable ICacheChangeListener<V> listener) 
    {
        listeners.remove(listener);
    }
    public @NonNullable Iterable<ICacheChangeListener<V>> getCacheChangeListeners() 
    {
        return listeners;
    }
    public void fireCacheChange(@NonNullable CacheChangeEvent<V> evt) 
    {
        for (ICacheChangeListener<V> listener : this.listeners) {
            listener.cacheChange(evt);
        }
    }
    public void fireCachePut(@NonNullable String key, @NonNullable V value) 
    {
        this.fireCacheChange(new CachePutEvent<V>(this.cache, key, value));
    }
    public void fireCacheRemove(@NonNullable String key) 
    {
        this.fireCacheChange(new CacheRemoveEvent<V>(this.cache, key));
    }
    public void fireCacheClear() 
    {
        this.fireCacheChange(new CacheClearEvent<V>(this.cache));
    }
}