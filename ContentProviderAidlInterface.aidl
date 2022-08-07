// ContentProviderAidlInterface.aidl
package com.cqueltech.sqlitedevstudio;

// Declare any non-default types here with import statements
import com.cqueltech.sqlitedevstudio.ContentProviderAidlCallback;

interface ContentProviderAidlInterface {

    void executeDatabaseOperation(
            String sqlType,
            String sql,
            String dbName,
            String accessCode,
            in ContentProviderAidlCallback callback,
            String table,
            in String[] projection,
            String selection,
            in String[] selectionArgs,
            String sortOrder,
            String limitStartPosition,
            String limitEndPosition,
            in ContentValues values,
            in ContentValues[] rows,
            boolean displayQueryResults);

    void cancelQuery();

    int getPid();
}
