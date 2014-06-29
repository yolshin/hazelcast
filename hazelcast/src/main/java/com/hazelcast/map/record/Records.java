package com.hazelcast.map.record;

public abstract class Records {

    public static void applyRecordInfo(Record record, RecordInfo replicationInfo) {
        record.setStatistics(replicationInfo.getStatistics());
        record.setVersion(replicationInfo.getVersion());
        record.setEvictionCriteriaNumber(replicationInfo.getEvictionCriteriaNumber());
        record.setTtl(replicationInfo.getTtl());
        record.setLastAccessTime(replicationInfo.getLastAccessTime());
        record.setLastUpdateTime(replicationInfo.getLastUpdateTime());
    }

    public static RecordInfo buildRecordInfo(Record record) {
        final RecordInfo info = new RecordInfo();
        info.setStatistics(record.getStatistics());
        info.setVersion(record.getVersion());
        info.setEvictionCriteriaNumber(record.getEvictionCriteriaNumber());
        info.setLastAccessTime(record.getLastAccessTime());
        info.setLastUpdateTime(record.getLastUpdateTime());
        info.setTtl(record.getTtl());
        return info;
    }


}
