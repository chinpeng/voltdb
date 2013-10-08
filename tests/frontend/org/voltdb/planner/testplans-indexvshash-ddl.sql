create table t (
  a bigint not null,
  b bigint not null,
  c bigint not null,
  d bigint not null,
  e bigint not null
);

create index idx_1 on t (a, b, c, d);
create index idx_2_TREE on t (e, a, b, c, d);

create index cover2_TREE on t (a, b);
create index cover3_TREE on t (a, c, b);


CREATE TABLE data_reports (
  reportID BIGINT NOT NULL,
  appID BIGINT NOT NULL,
  metricID BIGINT NOT NULL,
  time TIMESTAMP NOT NULL,
  value FLOAT DEFAULT '0' NOT NULL,
  field VARCHAR(10) DEFAULT 'value' NOT NULL,
  CONSTRAINT IDX_reportData_PK PRIMARY KEY (reportID,metricID,time,field)
);
PARTITION TABLE data_reports ON COLUMN appID;
