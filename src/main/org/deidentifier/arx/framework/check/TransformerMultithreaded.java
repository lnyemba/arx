/*
 * ARX: Powerful Data Anonymization
 * Copyright 2012 - 2015 Florian Kohlmayer, Fabian Prasser
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.deidentifier.arx.framework.check;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

import org.deidentifier.arx.ARXConfiguration.ARXConfigurationInternal;
import org.deidentifier.arx.framework.check.StateMachine.TransitionType;
import org.deidentifier.arx.framework.check.distribution.IntArrayDictionary;
import org.deidentifier.arx.framework.check.groupify.HashGroupify;
import org.deidentifier.arx.framework.check.groupify.HashGroupifyEntry;
import org.deidentifier.arx.framework.check.transformer.AbstractTransformer;
import org.deidentifier.arx.framework.data.GeneralizationHierarchy;

/**
 * A multi-threaded implementation of the Transformer class.
 * 
 * @author Fabian Prasser
 * @author Florian Kohlmayer
 */
public class TransformerMultithreaded extends Transformer {

    /** Overhead */
    private static final double                   OVERHEAD = 0.2d;

    /** Thread pool */
    private ExecutorService                       pool;
    /** Number of threads */
    private final int                             threads;
    /** Transformers */
    private final AbstractTransformer[][]         transformers;
    /** Groupifies */
    private final HashGroupify[]                  groupifies;
    /** Futures */
    private final List<Future<HashGroupifyEntry>> futures;
                                          
    /**
     * Instantiates a new transformer.
     *
     * @param inputGeneralized
     * @param inputAnalyzed
     * @param hierarchies
     * @param initialGroupifySize
     * @param config
     * @param dictionarySensValue
     * @param dictionarySensFreq
     */
    public TransformerMultithreaded(final int[][] inputGeneralized,
                                    final int[][] inputAnalyzed,
                                    final GeneralizationHierarchy[] hierarchies,
                                    final int initialGroupifySize,
                                    final ARXConfigurationInternal config,
                                    final IntArrayDictionary dictionarySensValue,
                                    final IntArrayDictionary dictionarySensFreq) {
                                    
        super(inputGeneralized,
              inputAnalyzed,
              hierarchies,
              config,
              dictionarySensValue,
              dictionarySensFreq);
              
        this.threads = config.getNumThreads();
        this.transformers = new AbstractTransformer[threads][];
        this.transformers[0] = super.getTransformers(); // Reuse
        for (int i = 1; i < threads; i++) {
            this.transformers[i] = super.createTransformers();
        }
        this.groupifies = new HashGroupify[this.threads - 1];
        this.futures = new ArrayList<Future<HashGroupifyEntry>>();
        for (int i = 0; i < groupifies.length; i++) {
            this.groupifies[i] = new HashGroupify(initialGroupifySize / threads, config);
        }
    }
    
    @Override
    public void shutdown() {
        if (pool != null) {
            pool.shutdown();
            pool = null;
        }
    }
    
    /**
     * Returns a transformer for a specific region of the dataset
     * @param projection
     * @param transformation
     * @param source
     * @param target
     * @param snapshot
     * @param transition
     * @param thread
     * @return
     */
    private AbstractTransformer getTransformer(long projection,
                                               int[] transformation,
                                               HashGroupify source,
                                               HashGroupify target,
                                               int[] snapshot,
                                               TransitionType transition,
                                               int startIndex,
                                               int stopIndex,
                                               int thread) {
                                               
        AbstractTransformer app = getTransformer(projection, transformers[thread]);
        app.init(projection,
                 transformation,
                 target,
                 source,
                 snapshot,
                 transition,
                 startIndex,
                 stopIndex);
                 
        return app;
    }
    
    public static int MULTITHREADED = 0;
    
    @Override
    protected void applyInternal(final long projection,
                                 final int[] transformation,
                                 final double collapseFactor,
                                 final HashGroupify source,
                                 final HashGroupify target,
                                 final int[] snapshot,
                                 final TransitionType transition) {
                                 
        // Determine total
        final int total;
        switch (transition) {
        case UNOPTIMIZED:
            total = getDataLength();
            break;
        case ROLLUP:
            total = source.getNumberOfEquivalenceClasses();
            break;
        case SNAPSHOT:
            total = snapshot.length / getSnapshotLength();
            break;
        default:
            throw new IllegalStateException("Unknown transition type");
        }
        
        // Create pool
        if (this.pool == null) {
            this.pool = Executors.newFixedThreadPool(threads - 1, new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread thread = new Thread(r);
                    thread.setDaemon(true);
                    thread.setName("ARX Transformer & Analyzer");
                    return thread;
                }
            });
        }
        
        // Calculate number of threads
        double factor = (collapseFactor * (double)getDataLength()) / (double)total; 
        int numThreads = getNumberOfThreads(factor, OVERHEAD);
        
        // Always use at least one thread, but not more than specified
        if (numThreads <= 0) {
            numThreads = 1;
        } else if (numThreads > threads) {
            numThreads = threads;
        }
        
        // Number of items per thread
        final int stepping = total / numThreads;
        
        // Clear futures
        futures.clear();
        
        // For each thread (if more than one)
        for (int i = 1; i < numThreads; i++) {
            
            // Execute
            final int thread = i;
            final int startIndex = thread * stepping;
            final int stopIndex = thread == threads - 1 ? total : (thread + 1) * stepping;

            // Worker thread
            futures.add(pool.submit(new Callable<HashGroupifyEntry>() {

                @Override
                public HashGroupifyEntry call() throws Exception {
                    getTransformer(projection,
                                   transformation,
                                   source,
                                   groupifies[thread - 1],
                                   snapshot,
                                   transition,
                                   startIndex,
                                   stopIndex,
                                   thread).call();
                                   
                    // Store result
                    HashGroupifyEntry result = groupifies[thread - 1].getFirstEquivalenceClass();
                    
                    // Free resources
                    groupifies[thread - 1].stateClear();
                    
                    // Return
                    return result;
                }
            }));
        }
        
        MULTITHREADED+=futures.size();
        
        // Prepare main thread
        final int thread = 0;
        final int startIndex = 0;
        final int stopIndex = numThreads == 1 ? total : (thread + 1) * stepping;
        
        // Main thread
        getTransformer(projection, transformation, source, target, snapshot, transition, startIndex, stopIndex, thread).call();
        
        // Collect results
        for (Future<HashGroupifyEntry> future : futures) {
            
            // Collect for this thread
            HashGroupifyEntry element = null;
            try {
                element = future.get();
            } catch (Exception e) {
                throw new RuntimeException("Error transforming data", e);
            }

            // Merge with main
            while (element != null) {
                
                // Add
                target.addFromThread(element.getHashcode(),
                                     element.getKey(),
                                     element.getDistributions(),
                                     element.getRepresentative(),
                                     element.getCount(),
                                     element.getPcount());
                                     
                // Next element
                element = element.getNextOrdered();
            }
        }
    }

    /**
     * Uses a model to compute the optimal number of threads for this operation
     * @param factor
     * @param overhead
     * @return
     */
    private int getNumberOfThreads(double factor, double overhead) {
        
        if (factor >= 1d) {
            return 1;
        }
        
        double threads = Math.floor((1d - factor) / (2d * overhead));
        double improvement = threads * threads * overhead + (factor - 1d) * threads + 1d;
        
        if (improvement < 0 && threads > 1) {
//            System.out.println("Computing number of threads");
//            System.out.println(" - Estimated collapse factor: " + factor);
//            System.out.println(" - Overhead: " + overhead);
//            System.out.println(" - Threads: " + threads);
//            System.out.println(" - Improvement: " + improvement);
            return (int)threads;
        } else {
            return 1;
        }
    }
}