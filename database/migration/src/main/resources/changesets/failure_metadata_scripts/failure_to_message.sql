UPDATE METADATA_ENTRY
SET METADATA_KEY =  CONCAT(TRIM(TRAILING 'failure' FROM METADATA_KEY), "message")
WHERE METADATA_KEY LIKE "%failures%failure";