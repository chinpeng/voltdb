/* This file is part of VoltDB.
 * Copyright (C) 2008-2014 VoltDB Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.voltdb.compiler;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import org.voltdb.common.Constants;
import org.voltdb.planner.BoundPlan;
import org.voltdb.utils.Encoder;

import com.google_voltpatches.common.cache.Cache;
import com.google_voltpatches.common.cache.CacheBuilder;

/**
 * Keep a cache two level cache of plans generated by the Ad Hoc
 * planner.
 *
 * First, store literals mapping to full plans that are
 * ready to execute.
 *
 * Second, store a string representation of a parameterized parsed
 * statement mapped to core parameterized plans. These parameterized
 * plans need parameter values and sql literals in order to be
 * actually used.
 */
public class AdHocCompilerCache implements Serializable {
    private static final long serialVersionUID = 1L;

    //////////////////////////////////////////////////////////////////////////
    // STATIC CODE TO MANAGE CACHE LIFETIMES / GLOBALNESS
    //////////////////////////////////////////////////////////////////////////

    // weak values should remove the object when the catalog hash is no longer needed
    private static Cache<String, AdHocCompilerCache> m_catalogHashMatch =
            CacheBuilder.newBuilder().weakValues().build();

    public static void clearHashCache() {
        m_catalogHashMatch.invalidateAll();
    }

    /**
     * Get the global cache for a given hash of the catalog. Note that there can be only
     * one cache per catalogHash at a time.
     */
    public synchronized static AdHocCompilerCache getCacheForCatalogHash(byte[] catalogHash) {
        String hashString = Encoder.hexEncode(catalogHash);
        AdHocCompilerCache cache = m_catalogHashMatch.getIfPresent(hashString);
        if (cache == null) {
            cache = new AdHocCompilerCache();
            m_catalogHashMatch.put(hashString, cache);
        }
        return cache;
    }

    //////////////////////////////////////////////////////////////////////////
    // PER-INSTANCE AWESOMEC CACHING CODE
    //////////////////////////////////////////////////////////////////////////

    // cache sizes determined at construction time
    final int MAX_LITERAL_ENTRIES;
    final int MAX_CORE_ENTRIES;

    /** cache of literals to full plans */
    final Map<String, AdHocPlannedStatement> m_literalCache;
    /** cache of parameterized plan descriptions to one or more core parameterized plans,
     *  each plan optionally has its own requirements for which parameters need to be bound
     *  to what values to enable its specialized (expression-indexed) plan. */
    final Map<String, List<BoundPlan> > m_coreCache;

    // placeholder stats used during development that may/may not survive
    long m_literalHits = 0;
    long m_literalQueries = 0;
    long m_literalInsertions = 0;
    long m_literalEvictions = 0;
    long m_planHits = 0;
    long m_planQueries = 0;
    long m_planInsertions = 0;
    long m_planEvictions = 0;

    /** {@see this#startPeriodicStatsPrinting() } */
    Timer m_statsTimer = null;

    /**
     * Constructor with default cache sizes.
     */
    private AdHocCompilerCache() {
        this(1000, 1000);
    }


    /**
     * Constructor with specific cache sizes is only called directly for testing.
     *
     * @param maxLiteralEntries cache size for literals
     * @param maxCoreEntries cache size for parameterized plans
     */
    AdHocCompilerCache(int maxLiteralEntries, int maxCoreEntries) {
        MAX_LITERAL_ENTRIES = maxLiteralEntries;
        MAX_CORE_ENTRIES = maxCoreEntries;

        // an LRU cache map
        m_literalCache = new LinkedHashMap<String, AdHocPlannedStatement>(MAX_LITERAL_ENTRIES * 2, .75f, true) {
            private static final long serialVersionUID = 1L;

            // This method is called just after a new entry has been added
            @Override
            public boolean removeEldestEntry(Map.Entry<String, AdHocPlannedStatement> eldest) {
                if (size() > MAX_LITERAL_ENTRIES) {
                    ++m_literalEvictions;
                    return true;
                }
                return false;
            }
        };

        // an LRU cache map
        m_coreCache = new LinkedHashMap<String, List<BoundPlan> >(MAX_CORE_ENTRIES * 2, .75f, true) {
            private static final long serialVersionUID = 1L;

            // This method is called just after a new entry has been added
            @Override
            public boolean removeEldestEntry(Map.Entry<String, List<BoundPlan> > eldest) {
                if (size() > MAX_CORE_ENTRIES) {
                    ++m_planEvictions;
                    return true;
                }
                return false;
            }
        };
    }

    /**
     * Stats printing method used during development.
     * Probably shouldn't live past real stats integration.
     */
    synchronized void printStats() {
        String line1 = String.format("CACHE STATS - Literals: Hits %d/%d (%.1f%%), Inserts %d Evictions %d\n",
                m_literalHits, m_literalQueries, (m_literalHits * 100.0) / m_literalQueries,
                m_literalInsertions, m_literalEvictions);
        String line2 = String.format("CACHE STATS - Plans:    Hits %d/%d (%.1f%%), Inserts %d Evictions %d\n",
                m_planHits, m_planQueries, (m_planHits * 100.0) /m_planQueries,
                m_planInsertions, m_planEvictions);

        System.out.print(line1 + line2);
        System.out.flush();

        // reset these
        m_literalHits = 0;
        m_literalQueries = 0;
        m_literalInsertions = 0;
        m_literalEvictions = 0;
        m_planHits = 0;
        m_planQueries = 0;
        m_planInsertions = 0;
        m_planEvictions = 0;
    }

    /**
     * @param sql SQL literal
     * @return full, ready-to-go plan
     */
    public synchronized AdHocPlannedStatement getWithSQL(String sql) {
        ++m_literalQueries;
        AdHocPlannedStatement retval = m_literalCache.get(sql);
        if (retval != null) {
            ++m_literalHits;
        }
        return retval;
    }

    /**
     * @param parsedToken String representing a parameterized and parsed
     * SQL statement
     * @return A CorePlan that needs parameter values to run.
     */
    public synchronized List<BoundPlan> getWithParsedToken(String parsedToken) {
        ++m_planQueries;
        List<BoundPlan> retval = m_coreCache.get(parsedToken);
        if (retval != null) {
            ++m_planHits;
        }
        return retval;
    }

    /**
     * Called from the PlannerTool directly when it finishes planning.
     * This is the only way to populate the cache.
     *
     * Note that one goal here is to reduce the number of times two
     * separate plan instances with the same value are input for the
     * same SQL literal.
     * @param sql               original query text
     * @param parsedToken       massaged query text, possibly with literals purged
     * @param planIn
     * @param extractedLiterals the basis values for any "bound parameter" restrictions to plan re-use
     */
    public synchronized void put(String sql,
                                 String parsedToken,
                                 AdHocPlannedStatement planIn,
                                 String[] extractedLiterals)
    {
        assert(sql != null);
        assert(parsedToken != null);
        assert(planIn != null);
        AdHocPlannedStatement plan = planIn;
        assert(new String(plan.sql, Constants.UTF8ENCODING).equals(sql));

        // uncomment this to get some raw stdout cache performance stats every 5s
        //startPeriodicStatsPrinting();

        BoundPlan matched = null;
        BoundPlan unmatched = new BoundPlan(planIn.core, planIn.parameterBindings(extractedLiterals));
        // deal with the parameterized plan cache first
        List<BoundPlan> boundVariants = m_coreCache.get(parsedToken);
        if (boundVariants == null) {
            boundVariants = new ArrayList<BoundPlan>();
            m_coreCache.put(parsedToken, boundVariants);
            // Note that there is an edge case in which more than one plan is getting counted as one
            // "plan insertion". This only happens when two different plans arose from the same parameterized
            // query (token) because one invocation used the correct constants to trigger an expression index and
            // another invocation did not.  These are not counted separately (which would have to happen below
            // after each call to boundVariants.add) because they are not evicted separately.
            // It seems saner to use consistent units when counting insertions vs. evictions.
            ++m_planInsertions;
        } else {
            for (BoundPlan boundPlan : boundVariants) {
                if (boundPlan.equals(unmatched)) {
                    matched = boundPlan;
                    break;
                }
            }
            if (matched != null) {
                // if a different core is found, reuse it
                // this is useful when updating the literal cache
                if (unmatched.m_core != matched.m_core) {
                    plan = new AdHocPlannedStatement(planIn, matched.m_core);
                    plan.setBoundConstants(matched.m_constants);
                }
            }
        }
        if (matched == null) {
            // Don't count insertions (of possibly repeated tokens) here
            //  -- see the comment above where only UNIQUE token insertions are being counted, instead.
            boundVariants.add(unmatched);
        }

        // then deal with the
        AdHocPlannedStatement cachedPlan = m_literalCache.get(sql);
        if (cachedPlan == null) {
            m_literalCache.put(sql, plan);
            ++m_literalInsertions;
        }
        else {
            assert(cachedPlan.equals(plan));
        }
    }

    /**
     * Start a timer that prints cache stats to the console every 5s.
     * Used for development until we get better stats integration.
     */
    public void startPeriodicStatsPrinting() {
        if (m_statsTimer == null) {
            m_statsTimer = new Timer();
            m_statsTimer.scheduleAtFixedRate(new TimerTask() {
                @Override
                public void run() {
                    printStats();
                }
            }, 5000, 5000);
        }
    }

    /**
     * Return the number of items in the literal cache.
     * @return  literal cache size as a count
     */
    public int getLiteralCacheSize() {
        return m_literalCache.size();
    }

    /**
     * Return the number of items in the core (parameterized) cache.
     * @return  core cache size as a count
     */
    public int getCoreCacheSize() {
        return m_coreCache.size();
    }
}
