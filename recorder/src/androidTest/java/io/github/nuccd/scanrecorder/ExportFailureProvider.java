package io.github.nuccd.scanrecorder;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Test APK process has no target APK Kotlin runtime, so this fixture uses only platform Java. */
public final class ExportFailureProvider extends ContentProvider {
  private final ConcurrentHashMap<String, AtomicInteger> receivedBytes = new ConcurrentHashMap<>();

  @Override
  public boolean onCreate() {
    return true;
  }

  @Override
  public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
    if (uri.getPathSegments().size() != 2
        || !"fail-write".equals(uri.getPathSegments().get(0))
        || !"wt".equals(mode)) {
      throw new FileNotFoundException("Unknown test export destination: " + uri);
    }
    AtomicInteger received = new AtomicInteger();
    receivedBytes.put(uri.getPathSegments().get(1), received);
    try {
      return interruptingDestination(received);
    } catch (IOException | ErrnoException exception) {
      FileNotFoundException failure = new FileNotFoundException("Cannot create test export channel");
      failure.initCause(exception);
      throw failure;
    }
  }

  private static ParcelFileDescriptor interruptingDestination(AtomicInteger received)
      throws IOException, ErrnoException {
    ParcelFileDescriptor[] channel = ParcelFileDescriptor.createSocketPair();
    try {
      Os.setsockoptInt(channel[1].getFileDescriptor(), OsConstants.SOL_SOCKET, OsConstants.SO_SNDBUF, 4096);
      Thread reader = new Thread(() -> readPrefix(channel[0], received), "export-test-reader");
      reader.setDaemon(true);
      reader.start();
      return channel[1];
    } catch (ErrnoException | RuntimeException exception) {
      for (ParcelFileDescriptor descriptor : channel) {
        try {
          descriptor.close();
        } catch (IOException ignored) {
          // Preserve the setup failure after attempting to close both descriptors.
        }
      }
      throw exception;
    }
  }

  private static void readPrefix(ParcelFileDescriptor descriptor, AtomicInteger received) {
    try (ParcelFileDescriptor.AutoCloseInputStream input =
        new ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
      received.set(Math.max(0, input.read(new byte[1024])));
    } catch (IOException exception) {
      received.set(-1);
    }
  }

  // Query progress over Binder rather than reading static fields from the instrumentation process.
  @Override
  public Bundle call(String method, String arg, Bundle extras) {
    if (!"receivedBytes".equals(method)) {
      throw new IllegalArgumentException("Unknown test method: " + method);
    }
    AtomicInteger received = arg == null ? null : receivedBytes.get(arg);
    Bundle result = new Bundle();
    result.putInt("receivedBytes", received == null ? 0 : received.get());
    return result;
  }

  @Override
  public String getType(Uri uri) {
    return "text/plain";
  }

  @Override
  public Cursor query(
      Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
    return null;
  }

  @Override
  public Uri insert(Uri uri, ContentValues values) {
    return null;
  }

  @Override
  public int delete(Uri uri, String selection, String[] selectionArgs) {
    return 0;
  }

  @Override
  public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
    return 0;
  }
}
