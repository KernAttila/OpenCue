
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

import com.imageworks.spcue.dispatcher.ResourceContainer;
import com.imageworks.spcue.grpc.host.HardwareState;
import com.imageworks.spcue.grpc.host.LockState;
import com.imageworks.spcue.util.CueUtil;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

public class DispatchHost extends Entity
        implements HostInterface, FacilityInterface, ResourceContainer {

    private static final Logger logger = LogManager.getLogger(DispatchHost.class);

    public String facilityId;
    public String allocationId;
    public LockState lockState;
    public HardwareState hardwareState;

    public int cores;
    public int idleCores;

    public int threads;
    public int idleThreads;

    public int gpus;
    public int idleGpus;

    // Basically an 0 = auto, 1 = all.
    public int threadMode;

    public long memory;
    public long idleMemory;
    public long gpuMemory;
    public long idleGpuMemory;
    public String tags;
    private String os;

    public boolean isNimby;
    public boolean isLocalDispatch = false;

    /**
     * Number of cores that will be added to the first proc booked to this host.
     */
    public int strandedCores = 0;
    public int strandedGpus = 0;

    // To reserve resources for future gpu job
    long idleMemoryOrig = 0;
    int idleCoresOrig = 0;
    int idleThreadsOrig = 0;
    long idleGpuMemoryOrig = 0;
    int idleGpusOrig = 0;

    public String getHostId() {
        return id;
    }

    public String getAllocationId() {
        return allocationId;
    }

    public String getFacilityId() {
        return facilityId;
    }

    public String[] getOs() {
        return this.os.split(",");
    }

    public void setOs(String os) {
        this.os = os;
    }

    public boolean canHandleNegativeCoresRequest(int requestedCores) {
        // Request is positive, no need to test further.
        if (requestedCores > 0) {
            logger.debug(getName() + " can handle the job with " + requestedCores + " cores.");
            return true;
        }
        // All cores are available, validate the request.
        if (cores == idleCores) {
            logger.debug(getName() + " can handle the job with " + requestedCores + " cores.");
            return true;
        }
        // Some or all cores are busy, avoid booking again.
        logger.debug(getName() + " cannot handle the job with " + requestedCores + " cores.");
        return false;
    }

    public int handleNegativeCoresRequirement(int requestedCores) {
        // If we request a <=0 amount of cores, return positive core count.
        // Request -2 on a 24 core machine will return 22.

        if (requestedCores > 0) {
            // Do not process positive core requests.
            logger.debug("Requested " + requestedCores + " cores.");
            return requestedCores;
        }
        if (requestedCores <= 0 && idleCores < cores) {
            // If request is negative but cores are already used, return 0.
            // We don't want to overbook the host.
            logger.debug("Requested " + requestedCores
                    + " cores, but the host is busy and cannot book more jobs.");
            return 0;
        }
        // Book all cores minus the request
        int totalCores = idleCores + requestedCores;
        logger.debug("Requested " + requestedCores + " cores  <= 0, " + idleCores
                + " cores are free, booking " + totalCores + " cores");
        return totalCores;
    }

    @Override
    public boolean hasAdditionalResources(int minCores, long minMemory, int minGpus,
            long minGpuMemory) {
        minCores = handleNegativeCoresRequirement(minCores);
        if (idleCores < minCores) {
            return false;
        }
        if (minCores <= 0) {
            return false;
        } else if (idleMemory < minMemory) {
            return false;
        } else if (idleGpus < minGpus) {
            return false;
        } else if (idleGpuMemory < minGpuMemory) {
            return false;
        }

        return true;
    }

    @Override
    public void useResources(int coreUnits, long memory, int gpuUnits, long gpuMemory) {
        idleCores = idleCores - coreUnits;
        idleMemory = idleMemory - memory;
        idleGpus = idleGpus - gpuUnits;
        idleGpuMemory = idleGpuMemory - gpuMemory;
    }

    /**
     * Unified method to check available resources for either cores or threads.
     *
     * @param frame DispatchFrame - frame containing resource requirements
     * @return boolean - whether host has sufficient resources
     */
    public boolean hasAdditionalResources(DispatchFrame frame) {
        int minComputeUnits = handleNegativeComputeUnitsRequirement(frame.getMinComputeUnits(), frame.useThreads);
        int idleComputeUnits = getIdleComputeUnits(frame.useThreads);

        if (idleComputeUnits < minComputeUnits) {
            return false;
        }
        if (minComputeUnits <= 0) {
            return false;
        } else if (idleMemory < frame.getMinMemory()) {
            return false;
        } else if (idleGpus < frame.minGpus) {
            return false;
        } else if (idleGpuMemory < frame.minGpuMemory) {
            return false;
        }

        return true;
    }

    /**
     * Unified method to consume resources for either cores or threads.
     *
     * @param frame DispatchFrame - frame containing resource requirements
     * @param computeUnits int - actual compute units to consume
     */
    public void useResources(DispatchFrame frame, int computeUnits) {
        if (frame.useThreads) {
            idleThreads = idleThreads - computeUnits;
        } else {
            idleCores = idleCores - computeUnits;
        }
        idleMemory = idleMemory - frame.getMinMemory();
        idleGpus = idleGpus - frame.minGpus;
        idleGpuMemory = idleGpuMemory - frame.minGpuMemory;
    }

    /**
     * If host has idle gpu, remove enough resources to book a gpu frame later.
     *
     */
    public void removeGpu() {
        if (idleGpuMemory > 0 && idleGpuMemoryOrig == 0) {
            idleMemoryOrig = idleMemory;
            idleCoresOrig = idleCores;
            idleGpuMemoryOrig = idleGpuMemory;
            idleGpusOrig = idleGpus;

            idleMemory = idleMemory - Math.min(CueUtil.GB4, idleMemory);
            idleCores = idleCores - Math.min(100, idleCores);
            idleGpuMemory = idleGpuMemory - Math.min(CueUtil.GB4, idleGpuMemory);
            idleGpus = idleGpus - Math.min(1, idleGpus);
        }
    }

    /**
     * If host had idle gpu removed, restore the host to the origional state.
     *
     */
    public void restoreGpu() {
        if (idleGpuMemoryOrig > 0) {
            idleMemory = idleMemoryOrig;
            idleCores = idleCoresOrig;
            idleThreads = idleThreadsOrig;
            idleGpuMemory = idleGpuMemoryOrig;
            idleGpus = idleGpusOrig;

            idleMemoryOrig = 0;
            idleCoresOrig = 0;
            idleThreadsOrig = 0;
            idleGpuMemoryOrig = 0;
            idleGpusOrig = 0;
        }
    }

    /**
     * Unified method to get available compute units (cores or threads).
     *
     * @param useThreads boolean - whether to return threads (true) or cores (false)
     * @return int - available compute units
     */
    public int getIdleComputeUnits(boolean useThreads) {
        return useThreads ? idleThreads : idleCores;
    }

    /**
     * Unified method to get total compute units (cores or threads).
     *
     * @param useThreads boolean - whether to return threads (true) or cores (false)
     * @return int - total compute units
     */
    public int getTotalComputeUnits(boolean useThreads) {
        return useThreads ? threads : cores;
    }

    /**
     * Unified method to handle negative compute unit requests (cores or threads).
     *
     * @param requestedUnits int - requested compute units (can be negative)
     * @param useThreads boolean - whether to use threads (true) or cores (false)
     * @return int - actual units to allocate
     */
    public int handleNegativeComputeUnitsRequirement(int requestedUnits, boolean useThreads) {
        int idleUnits = getIdleComputeUnits(useThreads);

        if (requestedUnits > 0) {
            logger.debug("Requested " + requestedUnits + " " + (useThreads ? "threads" : "cores"));
            return requestedUnits;
        }

        int totalUnits = getTotalComputeUnits(useThreads);
        if (requestedUnits <= 0 && idleUnits < totalUnits) {
            logger.debug("Requested " + requestedUnits + " " + (useThreads ? "threads" : "cores")
                    + ", but the host is busy and cannot book more jobs.");
            return 0;
        }

        int result = idleUnits + requestedUnits;
        logger.debug("Requested " + requestedUnits + " " + (useThreads ? "threads" : "cores")
                + " <= 0, " + idleUnits + " " + (useThreads ? "threads" : "cores")
                + " are free, booking " + result + " " + (useThreads ? "threads" : "cores"));
        return result;
    }

    /**
     * Unified method to check if host can handle negative compute unit requests.
     *
     * @param requestedUnits int - requested compute units
     * @param useThreads boolean - whether to use threads (true) or cores (false)
     * @return boolean - whether the request can be handled
     */
    public boolean canHandleNegativeComputeUnitsRequest(int requestedUnits, boolean useThreads) {
        if (requestedUnits > 0) {
            logger.debug(getName() + " can handle the job with " + requestedUnits + " " + (useThreads ? "threads" : "cores"));
            return true;
        }

        int idleUnits = getIdleComputeUnits(useThreads);
        int totalUnits = getTotalComputeUnits(useThreads);

        if (totalUnits == idleUnits) {
            logger.debug(getName() + " can handle the job with " + requestedUnits + " " + (useThreads ? "threads" : "cores"));
            return true;
        }

        logger.debug(getName() + " cannot handle the job with " + requestedUnits + " " + (useThreads ? "threads" : "cores"));
        return false;
    }
}
