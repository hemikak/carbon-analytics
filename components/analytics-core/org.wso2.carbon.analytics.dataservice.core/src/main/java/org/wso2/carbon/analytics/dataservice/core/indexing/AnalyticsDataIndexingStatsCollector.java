/*
 *  Copyright (c) 2014, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 *
 */
package org.wso2.carbon.analytics.dataservice.core.indexing;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * This class handles analytics data indexing statistics operations.
 */
public class AnalyticsDataIndexingStatsCollector extends TimerTask {

    private static Log log = LogFactory.getLog(AnalyticsDataIndexingStatsCollector.class);
    
    private AtomicLong fullCount = new AtomicLong();
    
    private AtomicLong lastCount = new AtomicLong();
    
    private static final int INTERVAL = 3000;
    
    public AnalyticsDataIndexingStatsCollector() {
        Timer timer = new Timer(true);
        timer.scheduleAtFixedRate(this, INTERVAL, INTERVAL);
    }
    
    public void processedRecords(int n) {
        this.fullCount.addAndGet(n);
    }
    
    public void calculateAndLog() {
        long recordsProcessed = this.fullCount.get() - this.lastCount.get();
        this.lastCount.set(this.fullCount.get());
        if (recordsProcessed > 0) {
            double tps = recordsProcessed / (double) INTERVAL * 1000;
            log.info("Indexing Statistics -> TPS: " + tps);
        }        
    }

    @Override
    public void run() {
        this.calculateAndLog();
    }
    
}
