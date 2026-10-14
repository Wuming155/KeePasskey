package com.keepasskey.app.data.repository;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.ProxyFileDescriptorCallback;
import android.os.storage.StorageManager;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import android.system.ErrnoException;
import android.system.OsConstants;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 594专属真实Binder/FD提供方；仅测试APK注册，清理只限本类UUID产物。 */
public final class SafCreationProbeDocumentsProvider extends DocumentsProvider {
    public static final String ROOT = "root594";
    private static final int PARTIAL_BYTES = 32;
    private static final String[] ROOT_COLUMNS = {
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.COLUMN_MIME_TYPES
    };
    private static final String[] DOCUMENT_COLUMNS = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE
    };

    private static final class Document {
        final File file;
        final String name;
        volatile String fault = "NONE";
        volatile int writes;
        volatile int deletes;

        Document(File file, String name) {
            this.file = file;
            this.name = name;
        }
    }

    private final Map<String, Document> documents = new LinkedHashMap<>();
    private Bundle result = new Bundle();
    private String registeredAuthority;
    private final HandlerThread worker = new HandlerThread("saf594-fd");
    private Handler handler;

    @Override
    public void attachInfo(android.content.Context context, ProviderInfo info) {
        if (info == null || info.authority == null) {
            throw new IllegalArgumentException("594 provider authority must be declared");
        }
        registeredAuthority = info.authority;
        super.attachInfo(context, info);
    }

    @Override
    public boolean onCreate() {
        worker.start();
        handler = new Handler(worker.getLooper());
        return true;
    }

    @Override
    public Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection == null ? ROOT_COLUMNS : projection);
        cursor.newRow()
                .add(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT)
                .add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT)
                .add(DocumentsContract.Root.COLUMN_TITLE, "SAF594 synthetic")
                .add(DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.FLAG_SUPPORTS_CREATE)
                .add(DocumentsContract.Root.COLUMN_MIME_TYPES, "application/octet-stream");
        return cursor;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection == null ? DOCUMENT_COLUMNS : projection);
        addDocument(cursor, documentId);
        return cursor;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection == null ? DOCUMENT_COLUMNS : projection);
        synchronized (documents) {
            for (String id : documents.keySet().toArray(new String[0])) addDocument(cursor, id);
        }
        return cursor;
    }

    private void addDocument(MatrixCursor cursor, String id) throws FileNotFoundException {
        Document document;
        synchronized (documents) {
            document = documents.get(id);
        }
        boolean root = ROOT.equals(id);
        if (!root && (document == null || !document.file.exists())) throw new FileNotFoundException(id);
        cursor.newRow()
                .add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, id)
                .add(DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        root ? "SAF594 synthetic" : ("NAME_MISSING".equals(document.fault) ? null : document.name))
                .add(DocumentsContract.Document.COLUMN_MIME_TYPE,
                        root ? DocumentsContract.Document.MIME_TYPE_DIR : "application/octet-stream")
                .add(DocumentsContract.Document.COLUMN_FLAGS,
                        root ? DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE
                                : DocumentsContract.Document.FLAG_SUPPORTS_WRITE
                                | DocumentsContract.Document.FLAG_SUPPORTS_DELETE)
                .add(DocumentsContract.Document.COLUMN_SIZE, root ? 0L : document.file.length());
    }

    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName)
            throws FileNotFoundException {
        if (!ROOT.equals(parentDocumentId)) throw new IllegalArgumentException("unexpected parent");
        String id = UUID.randomUUID().toString();
        File directory = new File(requireContext().getFilesDir(), "saf594");
        if (!directory.mkdirs() && !directory.isDirectory()) {
            throw new FileNotFoundException("cannot create SAF 594 directory");
        }
        synchronized (documents) {
            String name = displayName;
            int suffix = 1;
            while (hasExistingName(name)) {
                String base = displayName.endsWith(".kdbx")
                        ? displayName.substring(0, displayName.length() - ".kdbx".length())
                        : displayName;
                name = base + " (" + suffix++ + ").kdbx";
            }
            File file = new File(directory, id);
            try {
                if (!file.createNewFile()) throw new FileNotFoundException("UUID file already exists");
            } catch (IOException error) {
                FileNotFoundException wrapped = new FileNotFoundException(error.getMessage());
                wrapped.initCause(error);
                throw wrapped;
            }
            documents.put(id, new Document(file, name));
        }
        return id;
    }

    private boolean hasExistingName(String name) {
        for (Document document : documents.values()) {
            if (document.name.equals(name) && document.file.exists()) return true;
        }
        return false;
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        Document document = requireDocument(documentId);
        document.deletes++;
        if ("DELETE_FAIL".equals(document.fault)) {
            throw new FileNotFoundException("injected delete failure");
        }
        if (!document.file.delete()) throw new FileNotFoundException(documentId);
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal)
            throws FileNotFoundException {
        Document document = requireDocument(documentId);
        if ("STAT_UNKNOWN".equals(document.fault)) {
            ParcelFileDescriptor[] pipe;
            try {
                pipe = ParcelFileDescriptor.createReliablePipe();
            } catch (IOException error) {
                throw asFileNotFound(error);
            }
            closeQuietly(pipe[1]);
            return pipe[0];
        }
        if (("rw".equals(mode) || "rwt".equals(mode)) && "OPEN_FAIL".equals(document.fault)) {
            throw new FileNotFoundException("injected open failure");
        }
        if (("rw".equals(mode) || "rwt".equals(mode))
                && ("ZERO_WRITE".equals(document.fault) || "PARTIAL_WRITE".equals(document.fault)
                || "FSYNC_FAIL".equals(document.fault))) {
            if ("rwt".equals(mode)) setLength(document.file, 0L);
            return proxy(document);
        }
        if ("r".equals(mode) && "READ_MISMATCH".equals(document.fault) && document.file.length() > 0) {
            try (RandomAccessFile file = new RandomAccessFile(document.file, "rw")) {
                int firstByte = file.read();
                file.seek(0);
                file.write(firstByte ^ 0xff);
            } catch (IOException error) {
                throw asFileNotFound(error);
            }
        }
        return ParcelFileDescriptor.open(document.file, ParcelFileDescriptor.parseMode(mode));
    }

    private ParcelFileDescriptor proxy(Document document) {
        StorageManager storage = requireContext().getSystemService(StorageManager.class);
        if (storage == null) throw new IllegalStateException("StorageManager unavailable");
        try {
            return storage.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_WRITE,
                    new ProxyFileDescriptorCallback() {
                    @Override
                    public long onGetSize() {
                        return document.file.length();
                    }

                    @Override
                    public int onRead(long offset, int size, byte[] data) throws ErrnoException {
                        try (RandomAccessFile file = new RandomAccessFile(document.file, "r")) {
                            file.seek(offset);
                            return Math.max(0, file.read(data, 0, size));
                        } catch (IOException error) {
                            throw new ErrnoException("read", OsConstants.EIO, error);
                        }
                    }

                    @Override
                    public int onWrite(long offset, int size, byte[] data) throws ErrnoException {
                        document.writes++;
                        if ("ZERO_WRITE".equals(document.fault)) {
                            throw new ErrnoException("write", OsConstants.EIO);
                        }
                        int amount = "PARTIAL_WRITE".equals(document.fault)
                                ? Math.min(size, PARTIAL_BYTES) : size;
                        try (RandomAccessFile file = new RandomAccessFile(document.file, "rw")) {
                            file.seek(offset);
                            file.write(data, 0, amount);
                        } catch (IOException error) {
                            throw new ErrnoException("write", OsConstants.EIO, error);
                        }
                        if ("PARTIAL_WRITE".equals(document.fault)) {
                            throw new ErrnoException("write", OsConstants.EIO);
                        }
                        return amount;
                    }

                    @Override
                    public void onFsync() throws ErrnoException {
                        if ("FSYNC_FAIL".equals(document.fault)) {
                            throw new ErrnoException("fsync", OsConstants.EIO);
                        }
                        try (RandomAccessFile file = new RandomAccessFile(document.file, "rw")) {
                            file.getFD().sync();
                        } catch (IOException error) {
                            throw new ErrnoException("fsync", OsConstants.EIO, error);
                        }
                    }

                    @Override
                    public void onRelease() {
                    }
                    }, handler);
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!method.startsWith("594:")) return super.call(method, arg, extras);
        int callingUid = Binder.getCallingUid();
        boolean hasManageDocuments = requireContext().checkCallingPermission(Manifest.permission.MANAGE_DOCUMENTS)
                == PackageManager.PERMISSION_GRANTED;
        if (callingUid != Process.myUid() && !hasManageDocuments && !isGrantedControlTarget(callingUid)) {
            throw new SecurityException("SAF 594 test control requires its APK, target control grant, or MANAGE_DOCUMENTS");
        }
        Bundle values = extras == null ? new Bundle() : extras;
        switch (method) {
            case "594:create": {
                String id;
                try {
                    id = createDocument(ROOT, "application/octet-stream", requireString(values, "name"));
                } catch (FileNotFoundException error) {
                    throw new IllegalStateException(error);
                }
                Uri uri = DocumentsContract.buildDocumentUri(registeredAuthority, id);
                int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                if (values.getBoolean("persist", true)) flags |= Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION;
                String target = requireDeclaredInstrumentationTarget(requireString(values, "target"));
                requireContext().grantUriPermission(target, uri, flags);
                Bundle response = new Bundle();
                response.putString("uri", uri.toString());
                return response;
            }
            case "594:fault": {
                requireDocument(arg).fault = requireString(values, "fault");
                return new Bundle();
            }
            case "594:seed": {
                writeFile(requireDocument(arg).file, new byte[]{41, 42, 43});
                return new Bundle();
            }
            case "594:state": {
                Document document = requireDocument(arg);
                Bundle state = new Bundle();
                state.putBoolean("exists", document.file.exists());
                state.putLong("size", document.file.length());
                state.putInt("writes", document.writes);
                state.putInt("deletes", document.deletes);
                state.putByteArray("bytes", document.file.exists() ? readFile(document.file) : new byte[0]);
                return state;
            }
            case "594:clearResult":
                result = new Bundle();
                return new Bundle();
            case "594:result":
                return new Bundle(result);
            case "594:report":
                result = new Bundle(values);
                return new Bundle();
            case "594:cleanup": {
                synchronized (documents) {
                    for (Document document : documents.values()) document.file.delete();
                    documents.clear();
                }
                new File(requireContext().getFilesDir(), "saf594").delete();
                return new Bundle();
            }
            case "594:end": {
                String target = declaredInstrumentationTarget();
                Uri controlUri = controlUri();
                requireContext().revokeUriPermission(controlUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                Bundle response = new Bundle();
                response.putString("target", target);
                return response;
            }
            default:
                throw new IllegalArgumentException(method);
        }
    }

    private boolean isGrantedControlTarget(int callingUid) {
        String target = declaredInstrumentationTarget();
        int targetUid;
        try {
            targetUid = requireContext().getPackageManager().getApplicationInfo(target, 0).uid;
        } catch (PackageManager.NameNotFoundException error) {
            return false;
        }
        if (callingUid != targetUid) return false;
        int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        return requireContext().checkUriPermission(controlUri(), Binder.getCallingPid(), callingUid, flags)
                == PackageManager.PERMISSION_GRANTED;
    }

    private Uri controlUri() {
        return Uri.parse("content://" + registeredAuthority + "/" + SafCreationProbeActivity.CONTROL_PATH);
    }

    private Document requireDocument(String id) {
        synchronized (documents) {
            Document document = documents.get(id);
            if (document == null) throw new IllegalStateException("unknown SAF 594 document: " + id);
            return document;
        }
    }

    private String requireDeclaredInstrumentationTarget(String target) {
        String declared = declaredInstrumentationTarget();
        if (!declared.equals(target)) throw new SecurityException("target is not the declared instrumentation target");
        return declared;
    }

    private String declaredInstrumentationTarget() {
        PackageInfo packageInfo;
        try {
            packageInfo = requireContext().getPackageManager().getPackageInfo(
                    requireContext().getPackageName(), PackageManager.GET_INSTRUMENTATION);
        } catch (PackageManager.NameNotFoundException error) {
            throw new IllegalStateException(error);
        }
        if (packageInfo.instrumentation == null || packageInfo.instrumentation.length != 1
                || packageInfo.instrumentation[0].targetPackage == null) {
            throw new IllegalStateException("expected exactly one instrumentation target");
        }
        return packageInfo.instrumentation[0].targetPackage;
    }

    private static String requireString(Bundle bundle, String key) {
        String value = bundle.getString(key);
        if (value == null) throw new IllegalArgumentException("missing " + key);
        return value;
    }

    private static void writeFile(File file, byte[] bytes) {
        try (RandomAccessFile output = new RandomAccessFile(file, "rw")) {
            output.setLength(0L);
            output.write(bytes);
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    private static byte[] readFile(File file) {
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            byte[] bytes = new byte[(int) input.length()];
            input.readFully(bytes);
            return bytes;
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    private static void setLength(File file, long length) {
        try (RandomAccessFile output = new RandomAccessFile(file, "rw")) {
            output.setLength(length);
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    private static void closeQuietly(ParcelFileDescriptor descriptor) {
        try {
            descriptor.close();
        } catch (IOException ignored) {
        }
    }

    private static FileNotFoundException asFileNotFound(IOException error) {
        FileNotFoundException wrapped = new FileNotFoundException(error.getMessage());
        wrapped.initCause(error);
        return wrapped;
    }
}
