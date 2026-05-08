-- The workflow / trigger entities originally shipped with @TableLogic, which
-- caused deleteById() to soft-update `deleted=1`. The project convention is
-- hard-delete everywhere (see contributing.md), and the soft-delete path
-- collided with the (workspace_id, name, deleted) unique key whenever a name
-- was recreated and re-deleted: the second update tried to write a tombstone
-- that already existed.
--
-- The entity annotations are removed in this same change set so deleteById()
-- now performs a real DELETE. This migration purges any tombstones that the
-- old soft-delete path may have written, because the annotation-driven query
-- filter is no longer applied — a stale `deleted=1` row would otherwise show
-- up in list endpoints.

DELETE FROM mate_workflow WHERE deleted <> 0;
DELETE FROM mate_workflow_run WHERE deleted <> 0;
DELETE FROM mate_trigger WHERE deleted <> 0;
