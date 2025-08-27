-- Add thread support to OpenCue
-- Track both physical cores and logical threads for better resource management

-- Add thread tracking to host table
ALTER TABLE host ADD COLUMN int_threads BIGINT DEFAULT 0 NOT NULL;
ALTER TABLE host ADD COLUMN int_threads_idle BIGINT DEFAULT 0 NOT NULL;

-- Initialize host thread counts conservatively for existing hosts
-- Start with 1:1 ratio (threads = cores) to maintain existing behavior
-- Administrators can increase thread counts after migration based on actual hardware
UPDATE host SET 
    int_threads = int_cores,
    int_threads_idle = int_cores_idle
WHERE int_threads = 0 AND int_threads_idle = 0;

-- Add thread support to layer table
ALTER TABLE layer ADD COLUMN b_use_threads BOOLEAN DEFAULT false NOT NULL;
ALTER TABLE layer ADD COLUMN int_threads_min INT DEFAULT 0 NOT NULL;
ALTER TABLE layer ADD COLUMN int_threads_max INT DEFAULT 10000 NOT NULL;

-- Initialize thread limits based on existing core limits for existing data
-- This ensures smooth transition: thread limits start with same values as core limits
UPDATE layer SET 
    int_threads_min = int_cores_min,
    int_threads_max = CASE 
        WHEN int_cores_max = 0 THEN 10000  -- Handle the special case where max cores is 0 (unlimited)
        ELSE int_cores_max 
    END
WHERE int_threads_min = 0 AND int_threads_max = 10000;

-- Add thread support to proc table  
ALTER TABLE proc ADD COLUMN int_threads_reserved BIGINT DEFAULT 0 NOT NULL;
ALTER TABLE proc ADD COLUMN b_use_threads BOOLEAN DEFAULT false NOT NULL;

-- Add thread support to frame table
ALTER TABLE frame ADD COLUMN b_use_threads BOOLEAN DEFAULT false NOT NULL;

-- Add thread support to frame_history table for reporting
ALTER TABLE frame_history ADD COLUMN b_use_threads BOOLEAN DEFAULT false NOT NULL;

-- Add thread support to layer_history table for reporting  
ALTER TABLE layer_history ADD COLUMN b_use_threads BOOLEAN DEFAULT false NOT NULL;

-- Add thread support to job_resource table
ALTER TABLE job_resource ADD COLUMN int_threads BIGINT DEFAULT 0 NOT NULL;
ALTER TABLE job_resource ADD COLUMN int_min_threads INT DEFAULT 0 NOT NULL;
ALTER TABLE job_resource ADD COLUMN int_max_threads INT DEFAULT 10000 NOT NULL;

-- Initialize thread limits based on existing core limits for existing data
-- This ensures smooth transition: thread limits start with same values as core limits
UPDATE job_resource SET 
    int_min_threads = int_min_cores,
    int_max_threads = int_max_cores
WHERE int_min_threads = 0 AND int_max_threads = 10000;

-- Add thread support to layer_resource table
ALTER TABLE layer_resource ADD COLUMN int_threads BIGINT DEFAULT 0 NOT NULL;

-- Update comments for clarity
COMMENT ON COLUMN host.int_cores IS 'Number of physical CPU cores';
COMMENT ON COLUMN host.int_threads IS 'Number of logical CPU threads (cores * hyperthreading factor)';
COMMENT ON COLUMN layer.b_use_threads IS 'Whether this layer requests logical threads (true) or physical cores (false)';
COMMENT ON COLUMN layer.int_cores_min IS 'Minimum physical cores required for this layer';
COMMENT ON COLUMN layer.int_cores_max IS 'Maximum physical cores allowed for this layer';
COMMENT ON COLUMN layer.int_threads_min IS 'Minimum logical threads required for this layer';
COMMENT ON COLUMN layer.int_threads_max IS 'Maximum logical threads allowed for this layer';
COMMENT ON COLUMN proc.int_cores_reserved IS 'Physical cores reserved for this proc';
COMMENT ON COLUMN proc.int_threads_reserved IS 'Logical threads reserved for this proc';
COMMENT ON COLUMN job_resource.int_threads IS 'Total threads allocated to this job';
COMMENT ON COLUMN job_resource.int_min_cores IS 'Minimum physical cores required for this job';
COMMENT ON COLUMN job_resource.int_max_cores IS 'Maximum physical cores allowed for this job';
COMMENT ON COLUMN job_resource.int_min_threads IS 'Minimum logical threads required for this job';
COMMENT ON COLUMN job_resource.int_max_threads IS 'Maximum logical threads allowed for this job';
COMMENT ON COLUMN layer_resource.int_threads IS 'Total threads allocated to this layer';

-- Update trigger to handle thread support correctly
-- The trigger ensures that procs are allocated with valid resource amounts:
-- 1. For core-based jobs (b_use_threads = false): must have cores > 0
-- 2. For thread-based jobs (b_use_threads = true): must have threads > 0
-- 3. Zero allocations indicate allocation failure and should be prevented
CREATE OR REPLACE FUNCTION trigger__before_insert_proc()
RETURNS TRIGGER AS $body$
BEGIN
    -- Validate core-based allocation (traditional behavior)
    IF NEW.b_use_threads = false THEN
        IF NEW.int_cores_reserved <= 0 THEN
            RAISE EXCEPTION 'failed to allocate proc, tried to allocate % cores for core-based job', NEW.int_cores_reserved;
        END IF;
    END IF;
    
    -- Validate thread-based allocation (new functionality)
    IF NEW.b_use_threads = true THEN
        IF NEW.int_threads_reserved <= 0 THEN
            RAISE EXCEPTION 'failed to allocate proc, tried to allocate % threads for thread-based job', NEW.int_threads_reserved;
        END IF;
        -- For thread-based jobs, cores can be 0 or any value (they represent equivalent cores)
    END IF;
    
    RETURN NEW;
END;
$body$
LANGUAGE PLPGSQL;

-- Update trigger to verify host resources including threads
CREATE OR REPLACE FUNCTION trigger__verify_host_resources()
RETURNS TRIGGER AS $body$
BEGIN
    IF NEW.int_cores_idle < 0 THEN
        RAISE EXCEPTION 'unable to allocate additional core units';
    END IF;

    IF NEW.int_threads_idle < 0 THEN
        RAISE EXCEPTION 'unable to allocate additional thread units';
    END IF;

    IF NEW.int_mem_idle < 0 THEN
        RAISE EXCEPTION 'unable to allocate additional memory';
    END IF;

    IF NEW.int_gpus_idle < 0 THEN
        RAISE EXCEPTION 'unable to allocate additional GPU units';
    END IF;

    IF NEW.int_gpu_mem_idle < 0 THEN
        RAISE EXCEPTION 'unable to allocate additional GPU memory';
    END IF;
    RETURN NEW;
END;
$body$
LANGUAGE PLPGSQL;

-- Update the trigger to include thread idle checks
DROP TRIGGER IF EXISTS verify_host_resources ON host;
CREATE TRIGGER verify_host_resources BEFORE UPDATE ON host
FOR EACH ROW
   WHEN (NEW.int_cores_idle != OLD.int_cores_idle
        OR NEW.int_threads_idle != OLD.int_threads_idle
        OR NEW.int_mem_idle != OLD.int_mem_idle
        OR NEW.int_gpus_idle != OLD.int_gpus_idle
        OR NEW.int_gpu_mem_idle != OLD.int_gpu_mem_idle)
   EXECUTE PROCEDURE trigger__verify_host_resources();

-- Update trigger to verify job resources including threads
CREATE OR REPLACE FUNCTION trigger__verify_job_resources()
RETURNS TRIGGER AS $body$
BEGIN
    -- Check if the new cores exceeds max cores
    IF NEW.int_cores > NEW.int_max_cores THEN
        RAISE EXCEPTION 'job has exceeded max cores';
    END IF;
    
    -- Check if the new threads exceeds max threads 
    IF NEW.int_threads > NEW.int_max_threads THEN
        RAISE EXCEPTION 'job has exceeded max threads';
    END IF;
    
    IF NEW.int_gpus > NEW.int_max_gpus THEN
        RAISE EXCEPTION 'job has exceeded max GPU units';
    END IF;
    RETURN NEW;
END;
$body$
LANGUAGE PLPGSQL;

-- Update the trigger to include thread checks
DROP TRIGGER IF EXISTS verify_job_resources ON job_resource;
CREATE TRIGGER verify_job_resources BEFORE UPDATE ON job_resource
FOR EACH ROW
  WHEN (NEW.int_max_cores = OLD.int_max_cores AND NEW.int_cores > OLD.int_cores OR
        NEW.int_max_threads = OLD.int_max_threads AND NEW.int_threads > OLD.int_threads OR
        NEW.int_max_gpus = OLD.int_max_gpus AND NEW.int_gpus > OLD.int_gpus)
  EXECUTE PROCEDURE trigger__verify_job_resources();

-- Update trigger to handle proc updates for both cores and threads
CREATE OR REPLACE FUNCTION trigger__update_proc_update_layer()
RETURNS TRIGGER AS $body$
DECLARE
    lr RECORD;
BEGIN
     FOR lr IN (
        SELECT
          pk_layer
        FROM
          layer_stat
        WHERE
          pk_layer IN (OLD.pk_layer, NEW.pk_layer)
        ORDER BY layer_stat.pk_layer DESC
        ) LOOP

      IF lr.pk_layer = OLD.pk_layer THEN

        UPDATE layer_resource SET
          int_cores = int_cores - OLD.int_cores_reserved,
          int_threads = int_threads - OLD.int_threads_reserved,
          int_gpus = int_gpus - OLD.int_gpus_reserved
        WHERE
          pk_layer = OLD.pk_layer;

      ELSE

        UPDATE layer_resource SET
          int_cores = int_cores + NEW.int_cores_reserved,
          int_threads = int_threads + NEW.int_threads_reserved,
          int_gpus = int_gpus + NEW.int_gpus_reserved
       WHERE
          pk_layer = NEW.pk_layer;
       END IF;

    END LOOP;
    RETURN NULL;
END;
$body$
LANGUAGE PLPGSQL;

-- ============================================================================
-- DATA MIGRATION SUMMARY
-- ============================================================================
-- This migration handles the transition from core-only to unified core/thread system:
--
-- 1. Host Configuration:
--    - Thread counts initialized as 2x core counts (assumes hyperthreading)
--    - Administrators should verify and adjust actual thread capabilities
--
-- 2. Job Resource Limits:
--    - Thread limits initialized to match existing core limits
--    - Maintains existing job behavior until explicitly changed to use threads
--
-- 3. Layer Configuration:
--    - Thread limits initialized to match existing core limits
--    - All existing layers default to core-based (b_use_threads = false)
--    - New layers can be configured for thread-based allocation
--
-- 4. Backward Compatibility:
--    - Existing jobs continue to work with core-based allocation
--    - Core limits remain unchanged and functional
--    - Thread support is additive, not replacing core support
--
-- 5. Migration Steps for Administrators:
--    - Verify host thread counts match actual hardware capabilities
--    - Update RQD configuration to report correct thread counts
--    - Gradually migrate job submission to use thread-based allocation
--    - Update show defaults for new jobs to use appropriate limits
