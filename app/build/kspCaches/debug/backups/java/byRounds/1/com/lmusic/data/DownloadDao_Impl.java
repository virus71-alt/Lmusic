package com.lmusic.data;

import android.database.Cursor;
import android.os.CancellationSignal;
import androidx.annotation.NonNull;
import androidx.room.CoroutinesRoom;
import androidx.room.EntityDeletionOrUpdateAdapter;
import androidx.room.EntityInsertionAdapter;
import androidx.room.RoomDatabase;
import androidx.room.RoomSQLiteQuery;
import androidx.room.SharedSQLiteStatement;
import androidx.room.util.CursorUtil;
import androidx.room.util.DBUtil;
import androidx.sqlite.db.SupportSQLiteStatement;
import java.lang.Class;
import java.lang.Exception;
import java.lang.Integer;
import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.lang.SuppressWarnings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import javax.annotation.processing.Generated;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlinx.coroutines.flow.Flow;

@Generated("androidx.room.RoomProcessor")
@SuppressWarnings({"unchecked", "deprecation"})
public final class DownloadDao_Impl implements DownloadDao {
  private final RoomDatabase __db;

  private final EntityInsertionAdapter<DownloadRecord> __insertionAdapterOfDownloadRecord;

  private final EntityDeletionOrUpdateAdapter<DownloadRecord> __deletionAdapterOfDownloadRecord;

  private final SharedSQLiteStatement __preparedStmtOfUpdateThumbnailPath;

  public DownloadDao_Impl(@NonNull final RoomDatabase __db) {
    this.__db = __db;
    this.__insertionAdapterOfDownloadRecord = new EntityInsertionAdapter<DownloadRecord>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT OR ABORT INTO `downloads` (`id`,`youtubeUrl`,`title`,`channel`,`durationSeconds`,`thumbnailPath`,`fileUri`,`status`,`errorMessage`,`downloadedAt`) VALUES (nullif(?, 0),?,?,?,?,?,?,?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final DownloadRecord entity) {
        statement.bindLong(1, entity.getId());
        statement.bindString(2, entity.getYoutubeUrl());
        statement.bindString(3, entity.getTitle());
        if (entity.getChannel() == null) {
          statement.bindNull(4);
        } else {
          statement.bindString(4, entity.getChannel());
        }
        statement.bindLong(5, entity.getDurationSeconds());
        if (entity.getThumbnailPath() == null) {
          statement.bindNull(6);
        } else {
          statement.bindString(6, entity.getThumbnailPath());
        }
        if (entity.getFileUri() == null) {
          statement.bindNull(7);
        } else {
          statement.bindString(7, entity.getFileUri());
        }
        statement.bindString(8, entity.getStatus());
        if (entity.getErrorMessage() == null) {
          statement.bindNull(9);
        } else {
          statement.bindString(9, entity.getErrorMessage());
        }
        statement.bindLong(10, entity.getDownloadedAt());
      }
    };
    this.__deletionAdapterOfDownloadRecord = new EntityDeletionOrUpdateAdapter<DownloadRecord>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "DELETE FROM `downloads` WHERE `id` = ?";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final DownloadRecord entity) {
        statement.bindLong(1, entity.getId());
      }
    };
    this.__preparedStmtOfUpdateThumbnailPath = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "UPDATE downloads SET thumbnailPath = ? WHERE id = ?";
        return _query;
      }
    };
  }

  @Override
  public Object insert(final DownloadRecord record, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __insertionAdapterOfDownloadRecord.insert(record);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object delete(final DownloadRecord record, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __deletionAdapterOfDownloadRecord.handle(record);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object deleteAll(final List<DownloadRecord> records,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __deletionAdapterOfDownloadRecord.handleMultiple(records);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object updateThumbnailPath(final int id, final String path,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfUpdateThumbnailPath.acquire();
        int _argIndex = 1;
        _stmt.bindString(_argIndex, path);
        _argIndex = 2;
        _stmt.bindLong(_argIndex, id);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfUpdateThumbnailPath.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Flow<List<DownloadRecord>> getAllFlow() {
    final String _sql = "SELECT * FROM downloads ORDER BY downloadedAt DESC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"downloads"}, new Callable<List<DownloadRecord>>() {
      @Override
      @NonNull
      public List<DownloadRecord> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfYoutubeUrl = CursorUtil.getColumnIndexOrThrow(_cursor, "youtubeUrl");
          final int _cursorIndexOfTitle = CursorUtil.getColumnIndexOrThrow(_cursor, "title");
          final int _cursorIndexOfChannel = CursorUtil.getColumnIndexOrThrow(_cursor, "channel");
          final int _cursorIndexOfDurationSeconds = CursorUtil.getColumnIndexOrThrow(_cursor, "durationSeconds");
          final int _cursorIndexOfThumbnailPath = CursorUtil.getColumnIndexOrThrow(_cursor, "thumbnailPath");
          final int _cursorIndexOfFileUri = CursorUtil.getColumnIndexOrThrow(_cursor, "fileUri");
          final int _cursorIndexOfStatus = CursorUtil.getColumnIndexOrThrow(_cursor, "status");
          final int _cursorIndexOfErrorMessage = CursorUtil.getColumnIndexOrThrow(_cursor, "errorMessage");
          final int _cursorIndexOfDownloadedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "downloadedAt");
          final List<DownloadRecord> _result = new ArrayList<DownloadRecord>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final DownloadRecord _item;
            final int _tmpId;
            _tmpId = _cursor.getInt(_cursorIndexOfId);
            final String _tmpYoutubeUrl;
            _tmpYoutubeUrl = _cursor.getString(_cursorIndexOfYoutubeUrl);
            final String _tmpTitle;
            _tmpTitle = _cursor.getString(_cursorIndexOfTitle);
            final String _tmpChannel;
            if (_cursor.isNull(_cursorIndexOfChannel)) {
              _tmpChannel = null;
            } else {
              _tmpChannel = _cursor.getString(_cursorIndexOfChannel);
            }
            final long _tmpDurationSeconds;
            _tmpDurationSeconds = _cursor.getLong(_cursorIndexOfDurationSeconds);
            final String _tmpThumbnailPath;
            if (_cursor.isNull(_cursorIndexOfThumbnailPath)) {
              _tmpThumbnailPath = null;
            } else {
              _tmpThumbnailPath = _cursor.getString(_cursorIndexOfThumbnailPath);
            }
            final String _tmpFileUri;
            if (_cursor.isNull(_cursorIndexOfFileUri)) {
              _tmpFileUri = null;
            } else {
              _tmpFileUri = _cursor.getString(_cursorIndexOfFileUri);
            }
            final String _tmpStatus;
            _tmpStatus = _cursor.getString(_cursorIndexOfStatus);
            final String _tmpErrorMessage;
            if (_cursor.isNull(_cursorIndexOfErrorMessage)) {
              _tmpErrorMessage = null;
            } else {
              _tmpErrorMessage = _cursor.getString(_cursorIndexOfErrorMessage);
            }
            final long _tmpDownloadedAt;
            _tmpDownloadedAt = _cursor.getLong(_cursorIndexOfDownloadedAt);
            _item = new DownloadRecord(_tmpId,_tmpYoutubeUrl,_tmpTitle,_tmpChannel,_tmpDurationSeconds,_tmpThumbnailPath,_tmpFileUri,_tmpStatus,_tmpErrorMessage,_tmpDownloadedAt);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
        }
      }

      @Override
      protected void finalize() {
        _statement.release();
      }
    });
  }

  @Override
  public Object count(final Continuation<? super Integer> $completion) {
    final String _sql = "SELECT COUNT(*) FROM downloads";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<Integer>() {
      @Override
      @NonNull
      public Integer call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final Integer _result;
          if (_cursor.moveToFirst()) {
            final int _tmp;
            _tmp = _cursor.getInt(0);
            _result = _tmp;
          } else {
            _result = 0;
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @Override
  public Object countByUrl(final String url, final Continuation<? super Integer> $completion) {
    final String _sql = "SELECT COUNT(*) FROM downloads WHERE youtubeUrl = ? AND status = 'ok'";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindString(_argIndex, url);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<Integer>() {
      @Override
      @NonNull
      public Integer call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final Integer _result;
          if (_cursor.moveToFirst()) {
            final int _tmp;
            _tmp = _cursor.getInt(0);
            _result = _tmp;
          } else {
            _result = 0;
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @Override
  public Object getAll(final Continuation<? super List<DownloadRecord>> $completion) {
    final String _sql = "SELECT * FROM downloads ORDER BY downloadedAt DESC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<List<DownloadRecord>>() {
      @Override
      @NonNull
      public List<DownloadRecord> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfYoutubeUrl = CursorUtil.getColumnIndexOrThrow(_cursor, "youtubeUrl");
          final int _cursorIndexOfTitle = CursorUtil.getColumnIndexOrThrow(_cursor, "title");
          final int _cursorIndexOfChannel = CursorUtil.getColumnIndexOrThrow(_cursor, "channel");
          final int _cursorIndexOfDurationSeconds = CursorUtil.getColumnIndexOrThrow(_cursor, "durationSeconds");
          final int _cursorIndexOfThumbnailPath = CursorUtil.getColumnIndexOrThrow(_cursor, "thumbnailPath");
          final int _cursorIndexOfFileUri = CursorUtil.getColumnIndexOrThrow(_cursor, "fileUri");
          final int _cursorIndexOfStatus = CursorUtil.getColumnIndexOrThrow(_cursor, "status");
          final int _cursorIndexOfErrorMessage = CursorUtil.getColumnIndexOrThrow(_cursor, "errorMessage");
          final int _cursorIndexOfDownloadedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "downloadedAt");
          final List<DownloadRecord> _result = new ArrayList<DownloadRecord>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final DownloadRecord _item;
            final int _tmpId;
            _tmpId = _cursor.getInt(_cursorIndexOfId);
            final String _tmpYoutubeUrl;
            _tmpYoutubeUrl = _cursor.getString(_cursorIndexOfYoutubeUrl);
            final String _tmpTitle;
            _tmpTitle = _cursor.getString(_cursorIndexOfTitle);
            final String _tmpChannel;
            if (_cursor.isNull(_cursorIndexOfChannel)) {
              _tmpChannel = null;
            } else {
              _tmpChannel = _cursor.getString(_cursorIndexOfChannel);
            }
            final long _tmpDurationSeconds;
            _tmpDurationSeconds = _cursor.getLong(_cursorIndexOfDurationSeconds);
            final String _tmpThumbnailPath;
            if (_cursor.isNull(_cursorIndexOfThumbnailPath)) {
              _tmpThumbnailPath = null;
            } else {
              _tmpThumbnailPath = _cursor.getString(_cursorIndexOfThumbnailPath);
            }
            final String _tmpFileUri;
            if (_cursor.isNull(_cursorIndexOfFileUri)) {
              _tmpFileUri = null;
            } else {
              _tmpFileUri = _cursor.getString(_cursorIndexOfFileUri);
            }
            final String _tmpStatus;
            _tmpStatus = _cursor.getString(_cursorIndexOfStatus);
            final String _tmpErrorMessage;
            if (_cursor.isNull(_cursorIndexOfErrorMessage)) {
              _tmpErrorMessage = null;
            } else {
              _tmpErrorMessage = _cursor.getString(_cursorIndexOfErrorMessage);
            }
            final long _tmpDownloadedAt;
            _tmpDownloadedAt = _cursor.getLong(_cursorIndexOfDownloadedAt);
            _item = new DownloadRecord(_tmpId,_tmpYoutubeUrl,_tmpTitle,_tmpChannel,_tmpDurationSeconds,_tmpThumbnailPath,_tmpFileUri,_tmpStatus,_tmpErrorMessage,_tmpDownloadedAt);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @NonNull
  public static List<Class<?>> getRequiredConverters() {
    return Collections.emptyList();
  }
}
