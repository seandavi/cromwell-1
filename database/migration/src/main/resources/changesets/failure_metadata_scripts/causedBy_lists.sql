UPDATE METADATA_ENTRY
SET METADATA_KEY =  REPLACE(METADATA_KEY, "causedBy:", "causedBy[0]:")
WHERE METADATA_KEY LIKE "%failures%causedBy:%";