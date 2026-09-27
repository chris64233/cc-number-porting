-- hbm2ddl 建表后导入：H2 按行读取脚本，每条语句必须占一行。
CREATE TRIGGER trg_porting_event_no_update BEFORE UPDATE ON porting_event FOR EACH ROW CALL "com.chris64233.numberporting.testsupport.AppendOnlyEventTrigger";
CREATE TRIGGER trg_porting_event_no_delete BEFORE DELETE ON porting_event FOR EACH ROW CALL "com.chris64233.numberporting.testsupport.AppendOnlyEventTrigger";
