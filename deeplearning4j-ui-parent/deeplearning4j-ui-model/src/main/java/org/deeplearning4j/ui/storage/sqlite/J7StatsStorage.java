package org.deeplearning4j.ui.storage.sqlite;

import lombok.NonNull;
import org.deeplearning4j.api.storage.Persistable;
import org.deeplearning4j.api.storage.StatsStorage;
import org.deeplearning4j.api.storage.StatsStorageListener;
import org.deeplearning4j.api.storage.StorageMetaData;
import org.deeplearning4j.berkeley.Pair;

import java.io.*;
import java.sql.*;
import java.util.*;

/**
 * Created by Alex on 14/12/2016.
 */
public class J7StatsStorage implements StatsStorage {

    private static final String TABLE_NAME_METADATA = "StorageMetaData";
    private static final String TABLE_NAME_STATIC_INFO = "StaticInfo";
    private static final String TABLE_NAME_UPDATES = "Updates";

    private static final String INSERT_META_SQL
            = "INSERT OR REPLACE INTO " + TABLE_NAME_METADATA + " (SessionID, TypeID, ObjectClass, ObjectBytes) VALUES ( ?, ?, ?, ? );";
    private static final String INSERT_STATIC_SQL
            = "INSERT OR REPLACE INTO " + TABLE_NAME_STATIC_INFO + " (SessionID, TypeID, WorkerID, ObjectClass, ObjectBytes) VALUES ( ?, ?, ?, ?, ? );";
    private static final String INSERT_UPDATE_SQL
            = "INSERT OR REPLACE INTO " + TABLE_NAME_UPDATES + " (SessionID, TypeID, WorkerID, Timestamp, ObjectClass, ObjectBytes) VALUES ( ?, ?, ?, ?, ?, ? );";

    private final File file;
    private final Connection connection;
    private List<StatsStorageListener> listeners = new ArrayList<>();

    public J7StatsStorage(@NonNull File file) {
        this.file = file;
        if (!file.exists()) {

        }

        try {
            connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        } catch (Exception e) {
            throw new RuntimeException("Error ninializing J7StatsStorage instance", e);
        }

        try {
            initializeTables();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private void initializeTables() throws SQLException {

        //Need tables for:
        //(a) Metadata  -> session ID and type ID; class; StorageMetaData as a binary BLOB
        //(b) Static info -> session ID, type ID, worker ID, persistable class, persistable bytes
        //(c) Update info -> session ID, type ID, worker ID, timestamp, update class, update bytes

        //First: check if tables exist
        DatabaseMetaData meta = connection.getMetaData();
        ResultSet rs = meta.getTables(null, null, "%", null);
        boolean hasStorageMetaDataTable = false;
        boolean hasStaticInfoTable = false;
        boolean hasUpdatesTable = false;
        while(rs.next()){
            //3rd value: table name - http://docs.oracle.com/javase/6/docs/api/java/sql/DatabaseMetaData.html#getTables%28java.lang.String,%20java.lang.String,%20java.lang.String,%20java.lang.String[]%29
            String name = rs.getString(3).toLowerCase();
            if(TABLE_NAME_METADATA.equals(name)) hasStorageMetaDataTable = true;
            else if( TABLE_NAME_STATIC_INFO.equals(name)) hasStaticInfoTable = true;
            else if( TABLE_NAME_UPDATES.equals(name)) hasUpdatesTable = true;
        }



        Statement statement = connection.createStatement();

        if(!hasStorageMetaDataTable) {
            statement.executeUpdate(
                    "CREATE TABLE " + TABLE_NAME_METADATA + " (" +
                            "SessionID TEXT NOT NULL, " +
                            "TypeID TEXT NOT NULL, " +
                            "ObjectClass TEXT NOT NULL, " +
                            "ObjectBytes BLOB NOT NULL, " +
                            "PRIMARY KEY ( SessionID, TypeID )" +
                            ");");
        }

        if(!hasStaticInfoTable) {
            statement.executeUpdate(
                    "CREATE TABLE " + TABLE_NAME_STATIC_INFO + " (" +
                            "SessionID TEXT NOT NULL, " +
                            "TypeID TEXT NOT NULL, " +
                            "WorkerID TEXT NOT NULL, " +
                            "ObjectClass TEXT NOT NULL, " +
                            "ObjectBytes BLOB NOT NULL, " +
                            "PRIMARY KEY ( SessionID, TypeID, WorkerID )" +
                            ");");
        }

        if(!hasUpdatesTable) {
            statement.executeUpdate(
                    "CREATE TABLE " + TABLE_NAME_UPDATES + " (" +
                            "SessionID TEXT NOT NULL, " +
                            "TypeID TEXT NOT NULL, " +
                            "WorkerID TEXT NOT NULL, " +
                            "Timestamp INTEGER NOT NULL, " +
                            "ObjectClass TEXT NOT NULL, " +
                            "ObjectBytes BLOB NOT NULL, " +
                            "PRIMARY KEY ( SessionID, TypeID, WorkerID, Timestamp )" +
                            ");");
        }

        statement.close();

    }

    private static Pair<String,byte[]> serializeForDB(Object object){
        String classStr = object.getClass().getName();
        try(ByteArrayOutputStream baos = new ByteArrayOutputStream(); ObjectOutputStream oos = new ObjectOutputStream(baos)){
            oos.writeObject(object);
            oos.close();
            byte[] bytes = baos.toByteArray();
            return new Pair<>(classStr, bytes);
        } catch (IOException e){
            throw new RuntimeException("Error serializing object for storage", e);
        }
    }

    private static <T> T deserialize(byte[] bytes){
        try(ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(bytes))){
            return (T)ois.readObject();
        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    private <T> T queryAndGet(String sql){
        try(Statement statement = connection.createStatement()){
            ResultSet rs = statement.executeQuery(sql);
            if(!rs.next()) return null;
            byte[] bytes = rs.getBytes(5);
            return deserialize(bytes);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    private List<String> selectDistinct(String columnName, boolean queryMeta, boolean queryStatic, boolean queryUpdates,
                                        String conditionColumn, String conditionValue){
        Set<String> unique = new HashSet<>();

        try(Statement statement = connection.createStatement()){
            if(queryMeta) {
                queryHelper(statement, querySqlHelper(columnName, TABLE_NAME_METADATA, conditionColumn, conditionValue), unique);
            }

            if(queryStatic) {
                queryHelper(statement, querySqlHelper(columnName, TABLE_NAME_STATIC_INFO, conditionColumn, conditionValue), unique);
            }

            if(queryUpdates) {
                queryHelper(statement, querySqlHelper(columnName, TABLE_NAME_UPDATES, conditionColumn, conditionValue), unique);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        return new ArrayList<>(unique);
    }

    private String querySqlHelper(String columnName, String table, String conditionColumn, String conditionValue ){
        String unique = "SELECT DISTINCT " + columnName + " FROM " + table;
        if(conditionColumn != null){
            unique += " WHERE " + conditionColumn + " = '" + conditionValue + ",";
        }
        unique += ";";
        return unique;
    }

    private void queryHelper(Statement statement, String q, Set<String> unique) throws SQLException{
        ResultSet rs = statement.executeQuery(q);
        while (rs.next()) {
            String str = rs.getString(1);
            unique.add(str);
        }
    }

    @Override
    public void putStorageMetaData(StorageMetaData storageMetaData) {
        putStorageMetaData(Collections.singletonList(storageMetaData));
    }

    @Override
    public void putStorageMetaData(Collection<? extends StorageMetaData> collection) {
        try{
            PreparedStatement ps = connection.prepareStatement(INSERT_META_SQL);

            for(StorageMetaData storageMetaData : collection ) {
                Pair<String, byte[]> p = serializeForDB(storageMetaData);

                ps.setString(1, storageMetaData.getSessionID());
                ps.setString(2, storageMetaData.getTypeID());
                ps.setString(3, p.getFirst());
                ps.setObject(4, p.getSecond());
                ps.addBatch();
            }
            ps.executeUpdate();
            connection.commit();
        } catch (SQLException e){
            throw new RuntimeException(e);
        }
    }

    @Override
    public void putStaticInfo(Persistable staticInfo) {
        putStaticInfo(Collections.singletonList(staticInfo));
    }

    @Override
    public void putStaticInfo(Collection<? extends Persistable> collection) {
        try{
            PreparedStatement ps = connection.prepareStatement(INSERT_STATIC_SQL);

            for(Persistable p : collection ) {
                Pair<String, byte[]> pair = serializeForDB(p);

                ps.setString(1, p.getSessionID());
                ps.setString(2, p.getTypeID());
                ps.setString(3, p.getWorkerID());
                ps.setString(4, pair.getFirst());
                ps.setObject(5, pair.getSecond());
                ps.addBatch();
            }
            ps.executeUpdate();
            connection.commit();
        } catch (SQLException e){
            throw new RuntimeException(e);
        }
    }

    @Override
    public void putUpdate(Persistable update) {
        putUpdate(Collections.singletonList(update));
    }

    @Override
    public void putUpdate(Collection<? extends Persistable> collection) {
        try{
            PreparedStatement ps = connection.prepareStatement(INSERT_UPDATE_SQL);

            for(Persistable p : collection ) {
                Pair<String, byte[]> pair = serializeForDB(p);

                ps.setString(1, p.getSessionID());
                ps.setString(2, p.getTypeID());
                ps.setString(3, p.getWorkerID());
                ps.setLong(4, p.getTimeStamp());
                ps.setString(5, pair.getFirst());
                ps.setObject(6, pair.getSecond());
                ps.addBatch();
            }
            ps.executeUpdate();
            connection.commit();
        } catch (SQLException e){
            throw new RuntimeException(e);
        }
    }

    @Override
    public void close() throws IOException {
        try{
            connection.commit();
            connection.close();
        }catch (Exception e){
            throw new IOException(e);
        }
    }

    @Override
    public boolean isClosed() {
        try{
            return connection.isClosed();
        }catch (Exception e){
            return true;
        }
    }

    @Override
    public List<String> listSessionIDs() {
        return selectDistinct("SessionID", true, true, false, null, null);
    }

    @Override
    public boolean sessionExists(String sessionID) {
        String existsMetaSQL = "SELECT TOP SessionID FROM " + TABLE_NAME_METADATA + ";";
        String existsStaticSQL = "SELECT TOP SessionID FROM " + TABLE_NAME_STATIC_INFO + ";";

        try(Statement statement = connection.createStatement()){
            ResultSet rs = statement.executeQuery(existsMetaSQL);
            if(rs.next()){
                return true;
            }

            rs = statement.executeQuery(existsStaticSQL);
            return rs.next();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Persistable getStaticInfo(String sessionID, String typeID, String workerID) {
        String selectStaticSQL = "SELECT * FROM " + TABLE_NAME_STATIC_INFO + " WHERE SessionID = '" + sessionID
                + "' AND TypeID = '" + typeID + "' AND WorkerID = '" + workerID + "';";
        return queryAndGet(selectStaticSQL);
    }

    @Override
    public List<Persistable> getAllStaticInfos(String sessionID, String typeID) {
        String selectStaticSQL = "SELECT * FROM " + TABLE_NAME_STATIC_INFO + " WHERE SessionID = '" + sessionID
                + "' AND TypeID = '" + typeID + "';";
        try(Statement statement = connection.createStatement()){
            ResultSet rs = statement.executeQuery(selectStaticSQL);
            List<Persistable> out = new ArrayList<>();
            while(rs.next()){
                byte[] bytes = rs.getBytes(5);
                out.add((Persistable)deserialize(bytes));
            }
            return out;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<String> listTypeIDsForSession(String sessionID) {
        return selectDistinct("TypeID", true, true, true, "SessionID", sessionID);
    }

    @Override
    public List<String> listWorkerIDsForSession(String sessionID) {
        return selectDistinct("WorkerID", false, true, true, "SessionID", sessionID);
    }

    @Override
    public List<String> listWorkerIDsForSessionAndType(String sessionID, String typeID) {
        String uniqueStatic = "SELECT DISTINCT WorkerID FROM " + TABLE_NAME_STATIC_INFO + " WHERE SessionID = '" + sessionID + "' AND TypeID = '" + typeID + "';";
        String uniqueUpdates = "SELECT DISTINCT WorkerID FROM " + TABLE_NAME_UPDATES + " WHERE SessionID = '" + sessionID + "' AND TypeID = '" + typeID + "';";

        Set<String> unique = new HashSet<>();
        try(Statement statement = connection.createStatement()){
            ResultSet rs = statement.executeQuery(uniqueStatic);
            while (rs.next()) {
                String str = rs.getString(1);
                unique.add(str);
            }

            rs = statement.executeQuery(uniqueUpdates);
            while (rs.next()) {
                String str = rs.getString(1);
                unique.add(str);
            }

        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        return new ArrayList<>(unique);
    }

    @Override
    public int getNumUpdateRecordsFor(String sessionID) {
        String sql = "SELECT COUNT(*) FROM " + TABLE_NAME_UPDATES + " WHERE SessionID = '" + sessionID + "';";
        try(Statement statement = connection.createStatement()){
            return statement.executeQuery(sql).getInt(1);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public int getNumUpdateRecordsFor(String sessionID, String typeID, String workerID) {
        String sql = "SELECT COUNT(*) FROM " + TABLE_NAME_UPDATES + " WHERE SessionID = '" + sessionID + "' AND TypeID = '" + typeID
            + "' AND WorkerID = '" + workerID + "';";
        try(Statement statement = connection.createStatement()){
            return statement.executeQuery(sql).getInt(1);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Persistable getLatestUpdate(String sessionID, String typeID, String workerID) {
        String sql = "SELECT * FROM " + TABLE_NAME_UPDATES + " WHERE SessionID = '" + sessionID + "' AND TypeID = '" + typeID
                + "' AND WorkerID = '" + workerID + "' ORDER BY Timestamp DESC LIMIT 1;";
        return queryAndGet(sql);
    }

    @Override
    public Persistable getUpdate(String sessionID, String typeId, String workerID, long timestamp) {
        String sql = "SELECT * FROM " + TABLE_NAME_UPDATES + " WHERE SessionID = '" + sessionID + "' AND TypeID = '" + typeID
                + "' AND WorkerID = '" + workerID + "' AND Timestamp = '" + timestamp + "';";
        return queryAndGet(sql);
    }

    @Override
    public List<Persistable> getLatestUpdateAllWorkers(String sessionID, String typeID) {
//        String sql = "SELECT * FROM " + TABLE_NAME_UPDATES + " WHERE SessionID = '" + sessionID + " AND TypeID = '" + typeID + "' "
//                + " GROUP BY SessionID, WorkerID;";
//        try(Statement statement = connection.createStatement()){
//            return statement.executeQuery(sql).getInt(1);
//        } catch (SQLException e) {
//            throw new RuntimeException(e);
//        }
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public List<Persistable> getAllUpdatesAfter(String sessionID, String typeID, String workerID, long timestamp) {
        String sql = "SELECT * FROM " + TABLE_NAME_UPDATES + " WHERE SessionID = '" + sessionID + "' AND TypeID = '" + typeID + "' "
                + "AND Timestamp > " + timestamp + ";";
        try(Statement statement = connection.createStatement()){
            ResultSet rs = statement.executeQuery(sql);
            List<Persistable> out = new ArrayList<>();
            while(rs.next()){
                byte[] bytes = rs.getBytes(6);
                out.add((Persistable)deserialize(bytes));
            }
            return out;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public List<Persistable> getAllUpdatesAfter(String sessionID, String typeID, long timestamp) {
        String sql = "SELECT * FROM " + TABLE_NAME_UPDATES + " WHERE SessionID = '" + sessionID + "'  "
                + "AND Timestamp > " + timestamp + ";";
        try(Statement statement = connection.createStatement()){
            ResultSet rs = statement.executeQuery(sql);
            List<Persistable> out = new ArrayList<>();
            while(rs.next()){
                byte[] bytes = rs.getBytes(6);
                out.add((Persistable)deserialize(bytes));
            }
            return out;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public StorageMetaData getStorageMetaData(String sessionID, String typeID) {
        String sql = "SELECT * FROM " + TABLE_NAME_METADATA + " WHERE SessionID = '" + sessionID + "' AND TypeID = '" + typeID + "' LIMIT 1;";
        return queryAndGet(sql);
    }

    @Override
    public void registerStatsStorageListener(StatsStorageListener listener) {
        listeners.add(listener);
    }

    @Override
    public void deregisterStatsStorageListener(StatsStorageListener listener) {
        listeners.remove(listener);
    }

    @Override
    public void removeAllListeners() {
        listeners.clear();
    }

    @Override
    public List<StatsStorageListener> getListeners() {
        return new ArrayList<>(listeners);
    }
}
