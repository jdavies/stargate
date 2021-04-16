package io.stargate.grpc.payload;

import io.stargate.db.BoundStatement;
import io.stargate.db.Result.Prepared;
import io.stargate.db.Result.Rows;
import io.stargate.proto.QueryOuterClass.Payload;
import io.stargate.proto.QueryOuterClass.QueryParameters;
import java.nio.ByteBuffer;

public interface PayloadHandler {
  BoundStatement bindValues(Prepared prepared, Payload payload, ByteBuffer unsetValue)
      throws Exception;

  Payload processResult(Rows rows, QueryParameters parameters) throws Exception;
}