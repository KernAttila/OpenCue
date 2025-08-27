
/*
 * Copyright Contributors to the OpenCue Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.imageworks.spcue;

import com.imageworks.spcue.dispatcher.Dispatcher;
import com.imageworks.spcue.grpc.host.ThreadMode;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

public class VirtualProc extends FrameEntity implements ProcInterface {

    private static final Logger logger = LogManager.getLogger(VirtualProc.class);

    public String hostId;
    public String allocationId;
    public String frameId;
    public String hostName;
    public String os;
    public byte[] childProcesses;

    public boolean canHandleNegativeCoresRequest;
    public int coresReserved;
    public int threadsReserved;
    public long memoryReserved;
    public long memoryUsed;
    public long memoryMax;
    public long virtualMemoryUsed;
    public long virtualMemoryMax;

    public int gpusReserved;
    public long gpuMemoryReserved;
    public long gpuMemoryUsed;
    public long gpuMemoryMax;

    public boolean unbooked;
    public boolean usageRecorded = false;
    public boolean isLocalDispatch = false;

    public String getProcId() {
        return id;
    }

    public String getHostId() {
        return hostId;
    }

    public String getAllocationId() {
        return allocationId;
    }

    public String getFrameId() {
        return frameId;
    }

    public String getName() {
        return hostName;
    }

    /**
     * Unified method to get reserved compute units (cores or threads).
     *
     * @param useThreads boolean - whether to return threads (true) or cores (false)
     * @return int - reserved compute units
     */
    public int getReservedComputeUnits(boolean useThreads) {
        return useThreads ? threadsReserved : coresReserved;
    }

    /**
     * Unified method to set reserved compute units (cores or threads).
     *
     * @param units int - number of units to reserve
     * @param useThreads boolean - whether to set threads (true) or cores (false)
     */
    public void setReservedComputeUnits(int units, boolean useThreads) {
        if (useThreads) {
            threadsReserved = units;
        } else {
            coresReserved = units;
        }
    }

    /**
     * Build and return a proc in either fast or efficient mode.
     *
     * Efficient mode tries to assign one core per frame, but may upgrade the number of cores based
     * on memory usage.
     *
     * Fast mode books all the idle cores on the the host at one time.
     *
     * @param host
     * @param frame
     * @return
     */
    public static final VirtualProc build(DispatchHost host, DispatchFrame frame,
            String... selfishServices) {
        VirtualProc proc = new VirtualProc();
        proc.allocationId = host.getAllocationId();
        proc.hostId = host.getHostId();
        proc.frameId = null;
        proc.layerId = frame.getLayerId();
        proc.jobId = frame.getJobId();
        proc.showId = frame.getShowId();
        proc.facilityId = frame.getFacilityId();
        proc.os = frame.os;

        proc.hostName = host.getName();
        proc.unbooked = false;
        proc.isLocalDispatch = host.isLocalDispatch;

        proc.coresReserved = frame.minCores;
        proc.threadsReserved = frame.useThreads ? frame.minThreads : frame.minCores;
        proc.memoryReserved = frame.getMinMemory();
        proc.gpusReserved = frame.minGpus;
        proc.gpuMemoryReserved = frame.minGpuMemory;

        /*
         * Unified compute unit allocation logic for both cores and threads
         */
        allocateComputeUnits(proc, host, frame, selfishServices);

        return proc;
    }

    /**
     * Unified method to allocate compute units (cores or threads) based on frame requirements.
     * This eliminates code duplication between core and thread allocation logic.
     */
    private static void allocateComputeUnits(VirtualProc proc, DispatchHost host, DispatchFrame frame, String[] selfishServices) {
        // Handle cores allocation (always done)
        allocateSpecificComputeUnits(proc, host, frame, false, selfishServices);

        // Handle thread allocation based on useThreads flag
        if (frame.useThreads) {
            allocateSpecificComputeUnits(proc, host, frame, true, selfishServices);
        } else {
            // When not using threads, threadsReserved should match coresReserved
            proc.threadsReserved = proc.coresReserved;
        }
    }

    /**
     * Allocate specific compute units (cores or threads) using unified logic.
     */
    private static void allocateSpecificComputeUnits(VirtualProc proc, DispatchHost host, DispatchFrame frame, boolean useThreads, String[] selfishServices) {
        int minUnits = frame.getMinComputeUnits();
        int maxUnits = frame.getMaxComputeUnits();
        String unitsType = frame.getComputeUnitsType();

        // Add stranded cores if dealing with cores (threads don't have stranded concept yet)
        if (!useThreads && host.strandedCores > 0) {
            minUnits += host.strandedCores;
        }

        proc.canHandleNegativeCoresRequest = host.canHandleNegativeComputeUnitsRequest(minUnits, useThreads);

        int reservedUnits = minUnits;

        if (reservedUnits == 0) {
            logger.debug("Reserving all " + unitsType);
            reservedUnits = host.getTotalComputeUnits(useThreads);
        } else if (reservedUnits < 0) {
            logger.debug("Reserving all " + unitsType + " minus " + reservedUnits);
            reservedUnits = host.handleNegativeComputeUnitsRequirement(reservedUnits, useThreads);
        } else if (reservedUnits >= 100) {
            int originalUnits = reservedUnits;
            int idleUnits = host.getIdleComputeUnits(useThreads);

            int wholeUnits = (int) (Math.floor(idleUnits / 100.0));
            if (wholeUnits == 0) {
                throw new EntityException("The host had only a fraction of a " + unitsType.substring(0, unitsType.length()-1) + " remaining "
                        + "but the frame required " + minUnits);
            }

            // Apply thread mode logic (using existing threadMode field)
            if (host.threadMode == 1) { // ThreadMode.ALL_VALUE
                reservedUnits = wholeUnits * 100;
            } else {
                if (frame.threadable) {
                    if (selfishServices != null && frame.services != null
                            && containsSelfishService(frame.services.split(","), selfishServices)) {
                        reservedUnits = wholeUnits * 100;
                    } else {
                        if (host.idleMemory - frame.getMinMemory() <= Dispatcher.MEM_STRANDED_THRESHHOLD) {
                            reservedUnits = wholeUnits * 100;
                        } else {
                            if (useThreads) {
                                reservedUnits = getThreadSpan(host, frame.getMinMemory());
                            } else {
                                reservedUnits = getCoreSpan(host, frame.getMinMemory());
                            }
                        }
                    }
                    if (host.threadMode == 2 && reservedUnits <= 200) { // ThreadMode.VARIABLE_VALUE
                        reservedUnits = 200;
                        if (reservedUnits > idleUnits) {
                            throw new JobDispatchException(
                                    "Do not allow threadable frame running one " + unitsType.substring(0, unitsType.length()-1) + " on a ThreadMode.Variable host.");
                        }
                    }
                }
            }

            // Sanity checks
            if (reservedUnits < 100) {
                reservedUnits = 100;
            }

            if (reservedUnits < originalUnits) {
                reservedUnits = originalUnits;
            }

            if (maxUnits > 0 && reservedUnits >= maxUnits) {
                reservedUnits = maxUnits;
            }

            if (reservedUnits > idleUnits) {
                if (host.threadMode == 2 && frame.threadable && wholeUnits == 1) { // ThreadMode.VARIABLE_VALUE
                    throw new JobDispatchException(
                            "Do not allow threadable frame running one " + unitsType.substring(0, unitsType.length()-1) + " on a ThreadMode.Variable host.");
                }
                reservedUnits = wholeUnits * 100;
            }
        }

        // Don't thread non-threadable layers
        if (!frame.threadable && reservedUnits > 100) {
            reservedUnits = 100;
        }

        // Set the reserved units
        proc.setReservedComputeUnits(reservedUnits, useThreads);
    }

    /**
     * Thread span calculation (similar to getCoreSpan but for threads).
     * This can be enhanced later to have thread-specific logic if needed.
     */
    private static int getThreadSpan(DispatchHost host, long memory) {
        // For now, use similar logic as cores but applied to threads
        // This can be customized later based on threading requirements
        return getCoreSpan(host, memory);
    }

    private static final boolean containsSelfishService(String[] frameServices,
            String[] selfishServices) {
        for (String frameService : frameServices) {
            for (String selfishService : selfishServices) {
                if (frameService.equals(selfishService)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static final VirtualProc build(DispatchHost host, DispatchFrame frame,
            LocalHostAssignment lja) {

        VirtualProc proc = new VirtualProc();
        proc.allocationId = host.getAllocationId();
        proc.hostId = host.getHostId();
        proc.frameId = null;
        proc.layerId = frame.getLayerId();
        proc.jobId = frame.getJobId();
        proc.showId = frame.getShowId();
        proc.facilityId = frame.getFacilityId();
        proc.os = frame.os;

        proc.hostName = host.getName();
        proc.unbooked = false;
        proc.isLocalDispatch = host.isLocalDispatch;

        proc.coresReserved = lja.getThreads() * 100;
        proc.memoryReserved = frame.getMinMemory();
        proc.gpusReserved = frame.minGpus;
        proc.gpuMemoryReserved = frame.minGpuMemory;

        int wholeCores = (int) (Math.floor(host.idleCores / 100.0));
        if (wholeCores == 0) {
            throw new EntityException("The host had only a fraction of a core remaining "
                    + "but the frame required " + frame.minCores);
        }

        if (proc.coresReserved > host.idleCores) {
            proc.coresReserved = wholeCores * 100;
        }

        return proc;

    }

    /**
     * Allocates additional cores when the frame is using more 50% more than a single cores worth of
     * memory.
     *
     * @param host
     * @param minMemory
     * @return
     */
    public static int getCoreSpan(DispatchHost host, long minMemory) {
        int totalCores = (int) (Math.floor(host.cores / 100.0));
        int idleCores = (int) (Math.floor(host.idleCores / 100.0));
        if (idleCores < 1) {
            return 100;
        }

        long memPerCore = host.idleMemory / totalCores;
        double procs = minMemory / (double) memPerCore;
        int reserveCores = (int) (Math.round(procs)) * 100;

        return reserveCores;
    }
}
