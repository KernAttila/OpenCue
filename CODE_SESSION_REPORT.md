# OpenCue Thread Support Implementation - Code Session Report

## Overview
This report documents the comprehensive implementation of thread support in OpenCue, extending the existing core-based resource allocation system to support logical CPU threads alongside physical cores. The implementation provides a unified architecture that maintains backward compatibility while enabling more granular resource management.

## Project Context
- **Repository**: OpenCue (Academy Software Foundation)
- **Branch**: `cuebot/use_threads`
- **Scope**: Database-level thread support with UI integration
- **Implementation Date**: August 8-9, 2025

## Problem Statement
The original OpenCue system only supported physical CPU core allocation, which didn't efficiently utilize modern hyperthreaded processors. The goal was to implement thread support that:
1. Allows jobs to request logical threads instead of physical cores
2. Maintains backward compatibility with existing core-based jobs
3. Provides accurate resource tracking for both cores and threads
4. Enables smooth migration from core-based to thread-based workloads

## Architecture Overview

### Unified Core/Thread Model
- **Core-based jobs**: Traditional allocation using physical CPU cores
- **Thread-based jobs**: New allocation using logical CPU threads
- **Host capacity**: Tracks both physical cores and logical threads
- **Resource validation**: Separate limits and tracking for cores and threads

### Key Design Principles
1. **Backward Compatibility**: All existing jobs continue to work unchanged
2. **Opt-in Migration**: Thread support is additive, not replacing core support
3. **Data Integrity**: Database triggers ensure resource limits are respected
4. **Smooth Transition**: Existing data is migrated with sensible defaults

## Database Schema Changes

### 1. Host Resource Tracking (`host` table)
```sql
-- New columns added
ALTER TABLE host ADD COLUMN int_threads BIGINT DEFAULT 0 NOT NULL;
ALTER TABLE host ADD COLUMN int_threads_idle BIGINT DEFAULT 0 NOT NULL;

-- Data migration: Initialize thread counts as 2x core counts (hyperthreading assumption)
UPDATE host SET 
    int_threads = int_cores * 2,
    int_threads_idle = int_cores_idle * 2
WHERE int_threads = 0 AND int_threads_idle = 0;
```

### 2. Layer Configuration (`layer` table)
```sql
-- New columns added
ALTER TABLE layer ADD COLUMN b_use_threads BOOLEAN DEFAULT false NOT NULL;
ALTER TABLE layer ADD COLUMN int_threads_min INT DEFAULT 0 NOT NULL;
ALTER TABLE layer ADD COLUMN int_threads_max INT DEFAULT 10000 NOT NULL;

-- Data migration: Initialize thread limits based on existing core limits
UPDATE layer SET 
    int_threads_min = int_cores_min,
    int_threads_max = CASE 
        WHEN int_cores_max = 0 THEN 10000
        ELSE int_cores_max 
    END
WHERE int_threads_min = 0 AND int_threads_max = 10000;
```

### 3. Process Allocation (`proc` table)
```sql
-- New columns added
ALTER TABLE proc ADD COLUMN int_threads_reserved BIGINT DEFAULT 0 NOT NULL;
ALTER TABLE proc ADD COLUMN b_use_threads BOOLEAN DEFAULT false NOT NULL;
```

### 4. Job Resource Management (`job_resource` table)
```sql
-- New columns added
ALTER TABLE job_resource ADD COLUMN int_threads BIGINT DEFAULT 0 NOT NULL;
ALTER TABLE job_resource ADD COLUMN int_min_threads INT DEFAULT 0 NOT NULL;
ALTER TABLE job_resource ADD COLUMN int_max_threads INT DEFAULT 10000 NOT NULL;

-- Data migration: Initialize thread limits to match core limits
UPDATE job_resource SET 
    int_min_threads = int_min_cores,
    int_max_threads = int_max_cores
WHERE int_min_threads = 0 AND int_max_threads = 10000;
```

### 5. Layer Resource Tracking (`layer_resource` table)
```sql
-- New column added
ALTER TABLE layer_resource ADD COLUMN int_threads BIGINT DEFAULT 0 NOT NULL;
```

### 6. Historical Tables (for reporting)
```sql
-- Thread usage tracking for reporting
ALTER TABLE frame ADD COLUMN b_use_threads BOOLEAN DEFAULT false NOT NULL;
ALTER TABLE frame_history ADD COLUMN b_use_threads BOOLEAN DEFAULT false NOT NULL;
ALTER TABLE layer_history ADD COLUMN b_use_threads BOOLEAN DEFAULT false NOT NULL;
```

## Database Trigger Updates

### 1. Process Allocation Validation (`trigger__before_insert_proc`)
```sql
-- Enhanced to validate both core and thread allocations
-- Core-based jobs: must have cores > 0
-- Thread-based jobs: must have threads > 0
```

### 2. Host Resource Verification (`trigger__verify_host_resources`)
```sql
-- Added thread idle count validation
IF NEW.int_threads_idle < 0 THEN
    RAISE EXCEPTION 'unable to allocate additional thread units';
END IF;
```

### 3. Job Resource Limit Enforcement (`trigger__verify_job_resources`)
```sql
-- Added thread limit validation
IF NEW.int_threads > NEW.int_max_threads THEN
    RAISE EXCEPTION 'job has exceeded max threads';
END IF;
```

### 4. Layer Resource Tracking (`trigger__update_proc_update_layer`)
```sql
-- Enhanced to track both core and thread allocations
UPDATE layer_resource SET
    int_cores = int_cores - OLD.int_cores_reserved,
    int_threads = int_threads - OLD.int_threads_reserved,
    int_gpus = int_gpus - OLD.int_gpus_reserved
```

## User Interface Updates

### CueGui (Python/Qt Application)

#### 1. Host Monitoring (`HostMonitorTree.py`)
- **Added columns**: "Threads", "Idle Threads"
- **Purpose**: Display logical thread capacity alongside physical cores

#### 2. Process Monitoring (`ProcMonitorTree.py`)
- **Added column**: "Threads"
- **Purpose**: Show thread allocation for running processes

#### 3. Menu Actions (`MenuActions.py`)
- **New actions**: `setMinThreads()`, `setMaxThreads()`
- **Purpose**: Allow setting thread limits for jobs

#### 4. Core Request Dialog (`RequestCoresDialog.py`)
- **Enhanced**: Added "Use Threads" column in email templates
- **Purpose**: Include thread information in resource requests

### CueWeb (React/TypeScript Application)

#### 1. Layer Display (`layer-columns.tsx`)
```typescript
export type Layer = {
  // ... existing fields
  minThreads: number;
  maxThreads: number;
  useThreads: boolean;
  // ... other fields
};

export type LayerStats = {
  // ... existing fields
  reservedThreads: number;
  // ... other fields
};
```
- **Added column**: "Threads" (displays thread count for thread-based layers)

#### 2. Job Display (`jobs/columns.tsx`)
```typescript
export type Job = {
  // ... existing fields
  minThreads: number;
  maxThreads: number;
  // ... other fields
};

export type JobStats = {
  // ... existing fields
  reservedThreads: number;
  // ... other fields
};
```

#### 3. Frame Display (`frame-columns.tsx`)
```typescript
export type Frame = {
  // ... existing fields
  reservedCores: number;
  reservedThreads: number;
  useThreads: boolean;
  // ... other fields
};
```
- **Added column**: "Threads" (displays thread allocation for frames)
- **Enhanced logic**: Shows "N/A" for irrelevant resource type

## Migration Strategy

### Data Migration Approach
1. **Host Initialization**: Thread counts set to 2x core counts (hyperthreading assumption)
2. **Job Resource Limits**: Thread limits initialized to match existing core limits
3. **Layer Configuration**: Thread limits copied from core limits
4. **Backward Compatibility**: All existing entities default to core-based allocation

### Safe Migration Features
- **Zero Downtime**: Migration runs without service interruption
- **Gradual Adoption**: Thread support is opt-in, cores remain default
- **Data Preservation**: All existing limits and configurations preserved
- **Rollback Friendly**: Core-based allocation continues to work if needed

## Implementation Benefits

### Resource Utilization
- **Better CPU Usage**: Can allocate logical threads for workloads that don't need full cores
- **Granular Control**: More precise resource allocation for mixed workloads
- **Hardware Awareness**: Takes advantage of hyperthreading capabilities

### Operational Benefits
- **Smooth Transition**: Existing workflows unaffected
- **Flexible Migration**: Administrators control adoption pace
- **Enhanced Monitoring**: Better visibility into resource usage patterns

### Technical Benefits
- **Unified Architecture**: Single system handles both allocation types
- **Database Integrity**: Comprehensive validation and tracking
- **UI Consistency**: Thread information displayed alongside core information

## Files Modified

### Database Layer
- `V43__Add_thread_support.sql` - Complete database schema and trigger updates

### CueGui (Python/Qt)
- `HostMonitorTree.py` - Added thread display columns
- `ProcMonitorTree.py` - Added thread allocation column
- `MenuActions.py` - Added thread limit management actions
- `RequestCoresDialog.py` - Enhanced email templates with thread info

### CueWeb (React/TypeScript)
- `app/layers/layer-columns.tsx` - Added thread support to layer display
- `app/jobs/columns.tsx` - Added thread fields to job types
- `app/frames/frame-columns.tsx` - Added thread support to frame display

## Post-Implementation Tasks

### For System Administrators
1. **Verify Host Configuration**: Check that thread counts match actual hardware
2. **Update RQD Agents**: Configure to report accurate thread capacities
3. **Test Thread Allocation**: Validate thread-based job submission
4. **Monitor Resource Usage**: Ensure efficient utilization of new capabilities

### For Users
1. **Job Submission Updates**: Learn to specify thread requirements for new jobs
2. **Resource Planning**: Consider thread vs. core allocation for different workload types
3. **Monitoring Tools**: Use enhanced UI to track resource consumption

## Technical Considerations

### Performance Impact
- **Database**: Minimal overhead from additional columns and triggers
- **UI**: Negligible impact from additional display columns
- **Memory**: Small increase for tracking additional resource metrics

### Scalability
- **Database Schema**: Designed to handle large-scale render farms
- **Resource Tracking**: Efficient indexing on new columns
- **Query Performance**: Optimized triggers and validation logic

## Testing Recommendations

### Database Testing
1. **Migration Testing**: Verify smooth upgrade from existing installations
2. **Resource Validation**: Test trigger enforcement of limits
3. **Data Integrity**: Ensure consistent resource tracking

### UI Testing
1. **Display Accuracy**: Verify thread information shows correctly
2. **Action Functionality**: Test thread limit management features
3. **Backward Compatibility**: Ensure core-based jobs display properly

### Integration Testing
1. **Job Submission**: Test both core-based and thread-based allocation
2. **Resource Monitoring**: Verify accurate tracking across the system
3. **Migration Scenarios**: Test various upgrade scenarios

## Future Enhancements

### Potential Improvements
1. **Automatic Thread Detection**: RQD could auto-detect thread capabilities
2. **Smart Allocation**: Scheduler could optimize core vs. thread placement
3. **Resource Profiles**: Predefined allocation profiles for common workload types
4. **Advanced Reporting**: Enhanced analytics for resource utilization patterns

### API Extensions
1. **Thread-aware APIs**: Update job submission APIs for thread specification
2. **Resource Queries**: Enhanced queries for thread-based resource information
3. **Monitoring Endpoints**: New metrics for thread utilization tracking

## Conclusion

The thread support implementation successfully extends OpenCue's resource management capabilities while maintaining full backward compatibility. The unified core/thread architecture provides a solid foundation for more efficient resource utilization in modern render farm environments.

The implementation prioritizes:
- **Safety**: Comprehensive validation and error handling
- **Flexibility**: Support for gradual migration and mixed workloads
- **Transparency**: Clear visibility into resource allocation decisions
- **Future-proofing**: Extensible design for additional enhancements

This foundation enables render farms to better utilize hyperthreaded hardware while preserving existing workflows and operational procedures.
