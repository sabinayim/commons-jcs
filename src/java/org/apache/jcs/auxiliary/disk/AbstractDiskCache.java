package org.apache.jcs.auxiliary.disk;


/*
 * Copyright 2001-2004 The Apache Software Foundation.
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


import java.io.IOException;
import java.io.Serializable;
import java.util.Hashtable;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.jcs.auxiliary.AuxiliaryCache;
import org.apache.jcs.engine.CacheConstants;
import org.apache.jcs.engine.CacheEventQueue;
import org.apache.jcs.engine.CacheInfo;
import org.apache.jcs.engine.behavior.ICacheElement;
import org.apache.jcs.engine.behavior.ICacheEventQueue;
import org.apache.jcs.engine.behavior.ICacheListener;
import org.apache.jcs.utils.locking.ReadWriteLock;
import org.apache.jcs.utils.locking.ReadWriteLockManager;

/**
 * Abstract class providing a base implementation of a disk cache, which can
 * be easily extended to implement a disk cache for a specific perstistence
 * mechanism.
 *
 * When implementing the abstract methods note that while this base class
 * handles most things, it does not acquire or release any locks.
 * Implementations should do so as neccesary. This is mainly done to minimize
 * the time speant in critical sections.
 *
 * Error handling in this class needs to be addressed. Currently if an
 * exception is thrown by the persistence mechanism, this class destroys the
 * event queue. Should it also destory purgatory? Should it dispose itself?
 *
 * @version $Id$
 */
public abstract class AbstractDiskCache implements AuxiliaryCache, Serializable
{
    private static final Log log =
        LogFactory.getLog( AbstractDiskCache.class );

    /**
     * Map where elements are stored between being added to this cache and
     * actually spooled to disk. This allows puts to the disk cache to return
     * quickly, and the more expensive operation of serializing the elements
     * to persistent storage queued for later. If the elements are pulled into
     * the memory cache while the are still in purgatory, writing to disk can
     * be cancelled.
     */
    protected Hashtable purgatory = new Hashtable();

    /**
     * The CacheEventQueue where changes will be queued for asynchronous
     * updating of the persistent storage.
     */
    protected ICacheEventQueue cacheEventQueue;

    /**
     * Each instance of a Disk cache should use this lock to synchronize reads
     * and writes to the underlying storage mechansism.
     */
    protected ReadWriteLock lock = new ReadWriteLock();

    /** Manages locking for purgatory item manipulation. */
    protected ReadWriteLockManager locker = new ReadWriteLockManager();

    /**
     * Indicates whether the cache is 'alive', defined as having been
     * initialized, but not yet disposed.
     */
    protected boolean alive = false;

    /**
     * Every cache will have a name, subclasses must set this when they are
     * initialized.
     */
    protected String cacheName;

    /**
     * DEBUG: Keeps a count of the number of purgatory hits for debug messages
     */
    protected int purgHits = 0;

    // ----------------------------------------------------------- constructors

    public AbstractDiskCache( String cacheName )
    {
        this.cacheName = cacheName;

        this.cacheEventQueue = new CacheEventQueue( new MyCacheListener(),
                                                    CacheInfo.listenerId,
                                                    cacheName );
    }

    // ------------------------------------------------------- interface ICache

    /**
     * Adds the provided element to the cache. Element will be added to
     * purgatory, and then queued for later writing to the serialized storage
     * mechanism.
     *
     * @see org.apache.jcs.engine.behavior.ICache#update
     */
    public final void update( ICacheElement cacheElement )
        throws IOException
    {
        if ( log.isDebugEnabled() )
        {
            log.debug( "Putting element in purgatory, cacheName: " + cacheName
                    + ", key: " + cacheElement.getKey() );
        }

        try
        {
            // Wrap the CacheElement in a PurgatoryElement

            PurgatoryElement pe = new PurgatoryElement( cacheElement );

            // Indicates the the element is eligable to be spooled to disk,
            // this will remain true unless the item is pulled back into
            // memory.

            pe.setSpoolable( true );

            // Add the element to purgatory

            purgatory.put( pe.getKey(), pe );

            // Queue element for serialization

            cacheEventQueue.addPutEvent( pe );
        }
        catch ( IOException ex )
        {
            log.error( ex );

            cacheEventQueue.destroy();
        }
    }

    /**
     * @see AuxiliaryCache#get
     */
    public final ICacheElement get( Serializable key )
    {
        // If not alive, always return null.

        if ( !alive )
        {
            return null;
        }

        PurgatoryElement pe = ( PurgatoryElement ) purgatory.get( key );

        // If the element was found in purgatory

        if ( pe != null )
        {
            purgHits++;

            if ( log.isDebugEnabled() )
            {
                if ( purgHits % 100 == 0 )
                {
                    log.debug( "Purgatory hits = " + purgHits );
                }
            }

            // The element will go back to the memory cache, so set spoolable
            // to false, which will prevent the queue listener from serializing
            // the element.

            pe.setSpoolable( false );

            log.debug( "Found element in purgatory, cacheName: " + cacheName
                    + ", key: " + key );

            purgatory.remove( key );

            return pe.cacheElement;
        }

        // If we reach this point, element was not found in purgatory, so get
        // it from the cache.

        try
        {
            return doGet( key );
        }
        catch ( Exception e )
        {
            log.error( e );

            cacheEventQueue.destroy();
        }

        return null;
    }

    public abstract Set getGroupKeys(String groupName);

    /**
     * @see org.apache.jcs.engine.behavior.ICache#remove
     */
    public final boolean remove( Serializable key )
    {
        String keyAsString = key.toString();

        writeLock( keyAsString );
        try
        {
            // Remove element from purgatory if it is there

            purgatory.remove( key );

            // Remove from persistent store immediately

            doRemove( key );
        }
        finally
        {
            releaseLock( keyAsString );
        }

        return false;
    }

    /**
     * @see org.apache.jcs.engine.behavior.ICache#removeAll
     */
    public final void removeAll()
    {
        // Replace purgatory with a new empty hashtable

        purgatory = new Hashtable();

        // Remove all from persistent store immediately

        doRemoveAll();
    }

    /**
     * Adds a dispose request to the disk cache.
     */
    public final void dispose()
    {

        // FIXME: May lose the end of the queue, need to be more graceful
        cacheEventQueue.destroy();

        // Invoke any implementation specific disposal code
        doDispose();

        alive = false;

    }

    /**
     * @see ICache#getCacheName
     */
    public String getCacheName()
    {
        return cacheName;
    }

    /**
     * @see ICache#getStatus
     */
    public int getStatus()
    {
        return ( alive ? CacheConstants.STATUS_ALIVE : CacheConstants.STATUS_DISPOSED );
    }

    /**
     * Size cannot be determined without knowledge of the cache implementation,
     * so subclasses will need to implement this method.
     *
     * @see ICache#getSize
     */
    public abstract int getSize();

    /**
     * @see org.apache.jcs.engine.behavior.ICacheType#getCacheType
     *
     * @return Always returns DISK_CACHE since subclasses should all be of
     *         that type.
     */
    public int getCacheType()
    {
        return DISK_CACHE;
    }

    /**
     * Internally used write lock for purgatory item modification.
     *
     * @param id What name to lock on.
     */
    private void writeLock( String id )
    {
        try
        {
            locker.writeLock( id );
        }
        catch ( InterruptedException e )
        {
            // See note in readLock()

            log.error( "Was interrupted while acquiring read lock", e );
        }
        catch ( Throwable e )
        {

            log.error( e );
        }
    }

    /**
     * Internally used write lock for purgatory item modification.
     *
     * @param id What name to lock on.
     */
    private void releaseLock( String id )
    {
        try
        {
            locker.done( id );
        }
        catch ( IllegalStateException e )
        {
            log.warn( "Problem releasing lock", e );
        }
    }

    /**
     * Cache that implements the CacheListener interface, and calls appropriate
     * methods in its parent class.
     */
    private class MyCacheListener implements ICacheListener
    {
        private long listenerId = 0;

        /**
         * @see ICacheListener#getListenerId
         */
        public long getListenerId()
            throws IOException
        {
            return this.listenerId;
        }

        /**
         * @see ICacheListener#setListenerId
         */
        public void setListenerId( long id )
            throws IOException
        {
            this.listenerId = id;
        }

        /**
         * @see ICacheListener#handlePut
         *
         * NOTE: This checks if the element is a puratory element and behaves
         * differently depending. However since we have control over how
         * elements are added to the cache event queue, that may not be needed
         * ( they are always PurgatoryElements ).
         */
        public void handlePut( ICacheElement element )
            throws IOException
        {
            if ( alive )
            {
                // If the element is a PurgatoryElement we must check to see
                // if it is still spoolable, and remove it from purgatory.

                if ( element instanceof PurgatoryElement )
                {
                    PurgatoryElement pe = ( PurgatoryElement ) element;

                    String keyAsString = element.getKey().toString();

                    writeLock( keyAsString );

                    try
                    {
                        // If the element has already been removed from
                        // purgatory do nothing

                        if ( ! purgatory.contains( pe ) )
                        {
                            return;
                        }

                        element = pe.getCacheElement();

                        // If the element is still eligable, spool it.

                        if ( pe.isSpoolable() )
                        {
                            doUpdate( element );
                        }

                        // After the update has completed, it is safe to remove
                        // the element from purgatory.

                        purgatory.remove( element.getKey() );
                    }
                    finally
                    {
                        releaseLock( keyAsString );
                    }
                }
                else
                {
                    doUpdate( element );
                }
            }
        }

        /**
         * @see ICacheListener#handleRemove
         */
        public void handleRemove( String cacheName, Serializable key )
            throws IOException
        {
            if ( alive )
            {
                if ( doRemove( key ) )
                {
                    log.debug( "Element removed, key: " + key );
                }
            }
        }

        /**
         * @see ICacheListener#handleRemoveAll
         */
        public void handleRemoveAll( String cacheName )
            throws IOException
        {
            if ( alive )
            {
                doRemoveAll();
            }
        }

        /**
         * @see ICacheListener#handleDispose
         */
        public void handleDispose( String cacheName )
            throws IOException
        {
            if ( alive )
            {
                doDispose();
            }
        }
    }


    // ---------------------- subclasses should implement the following methods

    /**
     * Get a value from the persistent store.
     *
     * @param key Key to locate value for.
     * @return An object matching key, or null.
     */
    protected abstract ICacheElement doGet( Serializable key );

    /**
     * Add a cache element to the persistent store.
     */
    protected abstract void doUpdate( ICacheElement element );

    /**
     * Remove an object from the persistent store if found.
     *
     * @param key Key of object to remove.
     */
    protected abstract boolean doRemove( Serializable key );

    /**
     * Remove all objects from the persistent store.
     */
    protected abstract void doRemoveAll();

    /**
     * Dispose of the persistent store. Note that disposal of purgatory and
     * setting alive to false does NOT need to be done by this method.
     */
    protected abstract void doDispose();

}

