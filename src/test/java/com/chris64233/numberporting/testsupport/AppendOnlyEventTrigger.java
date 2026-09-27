package com.chris64233.numberporting.testsupport;

import java.sql.Connection;
import java.sql.SQLException;

import org.h2.api.Trigger;

/**
 * H2 触发器：从数据库层面拒绝 porting_event 的任何 UPDATE / DELETE。
 * 测试环境通过 hibernate.hbm2ddl.import_files 装配，验证事件真正只增不改不删
 * （生产数据库应以等价触发器 / 表权限授予保证）。
 */
public class AppendOnlyEventTrigger implements Trigger {

    @Override
    public void init(Connection conn, String schemaName, String triggerName,
                     String tableName, boolean before, int type) throws SQLException {
    }

    @Override
    public void fire(Connection conn, Object[] oldRow, Object[] newRow) throws SQLException {
        throw new SQLException("porting_event is append-only: update/delete is forbidden");
    }

    @Override
    public void close() throws SQLException {
    }

    @Override
    public void remove() throws SQLException {
    }
}
