package com.orientechnologies.orient.distributed.impl;

import com.orientechnologies.orient.core.db.ODatabaseInternal;
import com.orientechnologies.orient.core.db.ODatabaseSession;
import com.orientechnologies.orient.core.db.OrientDBInternal;
import com.orientechnologies.orient.core.storage.disk.OLocalPaginatedStorage;
import com.orientechnologies.orient.core.storage.impl.local.OAbstractPaginatedStorage;
import com.orientechnologies.orient.distributed.OrientDBDistributed;
import com.orientechnologies.orient.distributed.impl.coordinator.OLogId;
import com.orientechnologies.orient.distributed.impl.coordinator.OLogRequest;
import com.orientechnologies.orient.distributed.impl.coordinator.OOperationLog;
import com.orientechnologies.orient.distributed.impl.coordinator.OOperationLogEntry;
import com.orientechnologies.orient.server.distributed.ODistributedException;

import java.io.*;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicLong;

public class OPersistentOperationalLogV1 implements OOperationLog {


  private OLogRequestFactory factory;
  private boolean leader;
  private long term;

  public interface OLogRequestFactory {
    OLogRequest createLogRequest(int requestId);
  }

  private static class OpLogInfo {
    private int currentFileNum;
    private int firstFileNum;
    private long keepUntil;

    void fromStream(InputStream stream) {
      try {
        DataInputStream in = new DataInputStream(stream);
        int version = in.readInt();
        if (version != 0) {
          throw new ODistributedException("Wrong oplog info: version " + version);
        }
        currentFileNum = in.readInt();
        firstFileNum = in.readInt();
        keepUntil = in.readLong();
      } catch (IOException e) {
        throw new ODistributedException("Cannot read oplog info: " + e.getMessage());
      }
    }

    void toStream(OutputStream stream) {
      try {
        DataOutputStream out = new DataOutputStream(stream);
        out.writeInt(0);
        out.writeInt(currentFileNum);
        out.writeInt(firstFileNum);
        out.writeLong(keepUntil);
      } catch (Exception e) {
        throw new ODistributedException("Cannot write oplog info: " + e.getMessage());
      }
    }
  }

  protected static final long MAGIC = 6148914691236517205L; //101010101010101010101010101010101010101010101010101010101010101
  protected static final String OPLOG_INFO_FILE = "oplog.opl";
  protected static final String OPLOG_FILE = "oplog_$NUM$.opl";
  protected static final int LOG_ENTRIES_PER_FILE = 16 * 1024;

  private final String storagePath;
  private OpLogInfo info;

  private FileOutputStream fileOutput;
  private DataOutputStream stream;

  private AtomicLong inc;

  public static OOperationLog newInstance(String databaseName, OrientDBInternal context, OLogRequestFactory factory) {
    OAbstractPaginatedStorage s = ((OrientDBDistributed) context).getStorage(databaseName);
    if (s instanceof OLocalPaginatedStorage) {
      OLocalPaginatedStorage storage = (OLocalPaginatedStorage) s;
      OPersistentOperationalLogV1 result = new OPersistentOperationalLogV1(storage.getStoragePath().toString(), factory);
      result.scheduleLogPrune(context, databaseName);
      return result;
    } else {
      //TODO replace with in-memory impl!
      return new OIncrementOperationalLog();
    }
  }

  public OPersistentOperationalLogV1(String storageFolder, OLogRequestFactory factory) {
    this.factory = factory;
    this.storagePath = storageFolder;
    try {
      this.info = readInfo();
    } catch (FileNotFoundException e) {
      e.printStackTrace();
      throw new ODistributedException("cannot init oplog: " + e.getMessage());
    }
    this.stream = initStream(info);
    inc = readLastLogId();
  }

  private DataOutputStream initStream(OpLogInfo info) {
    String filePath = calculateLogFileFullPath(info.currentFileNum);
    try {
      File file = new File(filePath);
      if (!file.exists()) {
        file.createNewFile();
      }
      this.fileOutput = new FileOutputStream(file, true);
      return new DataOutputStream(fileOutput);
    } catch (IOException e) {
      throw new ODistributedException("Cannot create oplog file " + info.currentFileNum + ": " + e.getMessage());
    }
  }

  protected String calculateLogFileFullPath(int fileNum) {
    String fileName = OPLOG_FILE.replace("$NUM$", "" + fileNum);
    String fullPath = storagePath + File.separator + fileName;
    return fullPath;
  }

  private OpLogInfo readInfo() throws FileNotFoundException {
    File infoFile = new File(storagePath, OPLOG_INFO_FILE);
    OpLogInfo result = new OpLogInfo();
    if (infoFile.exists()) {
      info = new OpLogInfo();
      info.fromStream(new FileInputStream(infoFile));
//      writeInfo(infoFile, result);
    } else {
      initNewInfoFile(infoFile, result);
    }
    return result;
  }

  private void writeInfo(OpLogInfo info) {
    File infoFile = new File(storagePath, OPLOG_INFO_FILE);
    if (infoFile.exists()) {
      writeInfo(infoFile, info);
    } else {
      initNewInfoFile(infoFile, info);
    }

  }

  private void writeInfo(File infoFile, OpLogInfo result) {
    try {
      FileOutputStream stream = new FileOutputStream(infoFile);
      result.toStream(stream);
      stream.flush();
      stream.getFD().sync();
      stream.close();
    } catch (IOException e) {
      throw new ODistributedException("Cannot write oplog info:" + e.getMessage());
    }
  }

  private void initNewInfoFile(File infoFile, OpLogInfo result) {
    try {
      result.currentFileNum = 0;
      result.firstFileNum = 0;
      result.keepUntil = 0;
      infoFile.createNewFile();
      FileOutputStream stream = new FileOutputStream(infoFile);
      result.toStream(stream);
      stream.flush();
      stream.getFD().sync();
      stream.close();
    } catch (IOException e) {
      throw new ODistributedException("Cannot init oplog info:" + e.getMessage());
    }

  }

  @Override
  public OLogId lastPersistentLog() {
    return new OLogId(inc.get(), term);
  }

  protected AtomicLong readLastLogId() {
    String filePath = storagePath + File.separator + OPLOG_FILE.replace("$NUM$", "" + info.currentFileNum);
    File f = new File(filePath);
    try (RandomAccessFile file = new RandomAccessFile(filePath, "r");) {
      if (!f.exists()) {
        f.createNewFile();
      }
      if (file.length() == 0) {
        return new AtomicLong(info.currentFileNum * LOG_ENTRIES_PER_FILE - 1);
      }
      file.seek(file.length() - 8); //magic
      long magic = file.readLong();
      if (magic != MAGIC) {
        return new AtomicLong(recover());
      }
      file.seek(file.length() - 16); //length plus magic
      long size = file.readLong();

      file.seek(file.length() - 16 - size - 20);
      return new AtomicLong(file.readLong());
    } catch (IOException e) {
      return new AtomicLong(recover());
    }
  }

  private long recover() {
    String oldFilePath = storagePath + File.separator + OPLOG_FILE.replace("$NUM$", "" + info.currentFileNum);
    File oldFile = new File(oldFilePath);
    String newFilePath = storagePath + File.separator + OPLOG_FILE.replace("$NUM$", "" + info.currentFileNum) + "_temp";
    File newFile = new File(newFilePath);
    if (newFile.exists()) {
      newFile.delete();
    }
    try {
      newFile.createNewFile();

      DataInputStream readStream = new DataInputStream(new FileInputStream(oldFile));
      DataOutputStream writeStream = new DataOutputStream(new FileOutputStream(newFile));

      OOperationLogEntry record = readRecord(readStream);
      while (record != null) {
        writeRecord(writeStream, record.getLogId(), record.getRequest());
        record = readRecord(readStream);
      }
      readStream.close();
      writeStream.close();

      File oldFileCopy = new File(oldFile.toString() + "_copy");
      if (oldFileCopy.exists()) {
        oldFileCopy.delete();
      }
      oldFile.renameTo(oldFileCopy);
      newFile.renameTo(new File(oldFilePath));
      oldFile.delete();
    } catch (IOException e) {
      throw new ODistributedException("Cannot find oplog file: " + oldFilePath);
    }
    throw new UnsupportedOperationException();
  }

  @Override
  public OLogId log(OLogRequest request) {
    if (!leader) {
      throw new IllegalStateException("Cannot log on a non-leader node");
    }
    OLogId result;
    synchronized (inc) {
      result = createLogId();
      write(result, request);
      inc.notifyAll();
    }

    flush();
    return result;
  }

  protected OLogId createLogId() {
    return new OLogId(inc.incrementAndGet(), term);
  }

  private void flush() {
//    try {
//      this.fileOutput.getFD().sync();
//    } catch (IOException e) {
//      e.printStackTrace();
//    }
  }


  @Override
  public boolean logReceived(OLogId logId, OLogRequest request) {
    if (leader) {
      return false;
    }
    if (logId.getId() <= lastPersistentLog().getId()) {
      return false;
    }

    synchronized (inc) {
      try {

        while (logId.getId() > lastPersistentLog().getId() + 1) {
          inc.wait();
        }
        this.term = logId.getTerm();
        write(logId, request);
        inc.incrementAndGet();
        inc.notifyAll();
      } catch (InterruptedException ex) {
      }
    }

    flush();
    return true;
  }

  private void write(OLogId logId, OLogRequest request) {
    if (logId.getId() % LOG_ENTRIES_PER_FILE == 0) {
      createNewStreamFile();
    }
    writeRecord(stream, logId, request);
  }

  protected void writeRecord(DataOutputStream stream, OLogId logId, OLogRequest request) {
    ByteArrayOutputStream outArray = new ByteArrayOutputStream();
    DataOutput out = new DataOutputStream(outArray);

    try {
      request.serialize(out);
      byte[] packet = outArray.toByteArray();
      int packetLengthPlusPacket = packet.length + 4;

      stream.writeLong(logId.getId());
      stream.writeLong(logId.getTerm());
      stream.writeInt(packetLengthPlusPacket);
      stream.writeInt(request.getRequestType());
      stream.write(packet);
      stream.writeLong(packetLengthPlusPacket);
      stream.writeLong(MAGIC);
      stream.flush();

    } catch (IOException e) {
      throw new ODistributedException("Cannot write oplog: " + e.getMessage());
    }
  }

  protected OOperationLogEntry readRecord(DataInputStream stream) {
    try {
      long logId = stream.readLong();
      long term = stream.readLong();
      int totalPacketSize = stream.readInt();
      int packetType = stream.readInt();
      OLogRequest request = getCoordinateMessagesFactory().createLogRequest(packetType);
      request.deserialize(stream);
      stream.readLong();//length, again
      long magic = stream.readLong();
      if (magic != MAGIC) {
        throw new ODistributedException("Invalid OpLog magic number for entry " + logId);
      }
      return new OOperationLogEntry(new OLogId(logId, term), request);
    } catch (Exception e) {
      return null;
    }
  }

  protected OLogRequestFactory getCoordinateMessagesFactory() {
    return factory;
  }

  private void createNewStreamFile() {
    info.currentFileNum = (int) (this.inc.get() / LOG_ENTRIES_PER_FILE);
    File infoFile = new File(storagePath, OPLOG_INFO_FILE);
    writeInfo(infoFile, info);
    if (stream != null) {
      try {
        stream.close();
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
    stream = initStream(info);
  }

  @Override
  public Iterator<OOperationLogEntry> iterate(long from, long to) {
    return new OPersistentOperationalLogIterator(this, from, to);
  }

  public synchronized void cutUntil(ODatabaseSession db, OLogId logId) {
    info.keepUntil = logId.getId();
    writeInfo(info);
    scheduleLogPrune(db);
  }

  private void scheduleLogPrune(ODatabaseSession db) {
    scheduleLogPrune(((ODatabaseInternal) db).getSharedContext().getOrientDB(), db.getName());
  }

  private void scheduleLogPrune(OrientDBInternal orient, String dbName) {
    orient.executeNoAuthorization(dbName, x -> {
      long lastFileId = info.keepUntil / LOG_ENTRIES_PER_FILE;
      for (int i = 0; i < lastFileId; i++) {
        String fileName = calculateLogFileFullPath(i);
        File file = new File(fileName);
        if (file.exists()) {
          try {
            file.delete();
          } catch (Exception e) {

          }
        }
      }
      return null;
    });
  }

  public void close() {
    if (stream != null) {
      try {
        stream.close();
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
  }

  @Override
  public LogIdStatus removeAfter(OLogId id) throws IllegalStateException {
    synchronized (inc) {
      LogIdStatus status = searchId(id);
      switch (status) {
      case TOO_OLD:
        removeAllLogFiles();
        inc.set(id.getId());
        break;
      case INVALID:
        break;
      case FUTURE:
        break;
      case PRESENT:
        doRemoveAfter(id);
        break;
      default:
        throw new IllegalArgumentException("Invalid log ID: " + id);
      }
      return status;
    }
  }

  private void doRemoveAfter(OLogId id) {
    try {
      stream.close();
      stream = null;
    } catch (IOException e) {
      e.printStackTrace();
    }

    int currentFileId = (int) (inc.get() / LOG_ENTRIES_PER_FILE);
    int newLastFileId = (int) (id.getId() / LOG_ENTRIES_PER_FILE);

    for (int i = newLastFileId; i < currentFileId; i++) {
      String fileName = calculateLogFileFullPath(i + 1);
      File file = new File(fileName);
      if (file.exists()) {
        try {
          file.delete();
        } catch (Exception e) {
        }
      }
    }
    removeAfterInFile(newLastFileId, id.getId());

    inc.set(id.getId());
    createNewStreamFile();
  }

  /**
   * @param fileId     the file ID
   * @param untilLogId last log ID to be kept
   */
  private void removeAfterInFile(int fileId, long untilLogId) {
    String oldFilePath = storagePath + File.separator + OPLOG_FILE.replace("$NUM$", "" + fileId);
    File oldFile = new File(oldFilePath);
    String newFilePath = storagePath + File.separator + OPLOG_FILE.replace("$NUM$", "" + fileId) + "_temp";
    File newFile = new File(newFilePath);
    if (newFile.exists()) {
      newFile.delete();
    }
    try {
      newFile.createNewFile();

      FileOutputStream outFileStream = new FileOutputStream(newFile);
      DataInputStream readStream = new DataInputStream(new FileInputStream(oldFile));
      DataOutputStream writeStream = new DataOutputStream(outFileStream);

      OOperationLogEntry record = readRecord(readStream);
      while (record != null && record.getLogId().getId() <= untilLogId) {
        writeRecord(writeStream, record.getLogId(), record.getRequest());
        record = readRecord(readStream);
      }
      readStream.close();
      outFileStream.getFD().sync();
      writeStream.close();

      File oldFileCopy = new File(oldFile.toString() + "_copy");
      if (oldFileCopy.exists()) {
        oldFileCopy.delete();
      }
      oldFile.renameTo(oldFileCopy);
      newFile.renameTo(new File(oldFilePath));
      oldFileCopy.delete();
    } catch (IOException e) {
      throw new ODistributedException("Cannot find oplog file: " + oldFilePath);
    }
  }

  private void removeAllLogFiles() {
    try {
      stream.close();
      stream = null;
    } catch (IOException e) {
      e.printStackTrace();
    }
    for (int i = 0; i <= inc.get() / LOG_ENTRIES_PER_FILE; i++) {
      String fileName = calculateLogFileFullPath(i);
      File file = new File(fileName);
      if (file.exists()) {
        try {
          file.delete();
        } catch (Exception e) {
        }
      }
      info.firstFileNum = i + 1;
    }
    writeInfo(info);
    createNewStreamFile();
  }

  public LogIdStatus searchId(OLogId logId) {
    long sequential = logId.getId();
    if (sequential > inc.get()) {
      return LogIdStatus.FUTURE;
    }
    OLogId oldestId = getOldestId();
    if (oldestId == null || oldestId.getId() > logId.getId()) {
      return LogIdStatus.TOO_OLD;
    }
    Iterator<OOperationLogEntry> iterator = iterate(logId.getId(), logId.getId());
    if (!iterator.hasNext()) {
      return LogIdStatus.INVALID;
    } else {
      OOperationLogEntry loaded = iterator.next();
      if (loaded.getLogId().equals(logId)) {
        return LogIdStatus.PRESENT;
      } else {
        return LogIdStatus.INVALID;
      }
    }
  }


  protected OLogId getOldestId() {
    Iterator<OOperationLogEntry> iterator = iterate(0, lastPersistentLog().getId());
    return iterator.hasNext() ? iterator.next().getLogId() : null;
  }

  @Override
  public void setLeader(boolean leader, long term) {
    synchronized (inc) {
      this.leader = leader;
      this.term = term;
    }
  }
}
