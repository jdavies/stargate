package io.stargate.grpc.codec.cql;

import com.google.protobuf.ByteString;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.grpc.Status;
import io.grpc.StatusException;
import io.stargate.db.schema.Column;
import io.stargate.proto.QueryOuterClass;
import io.stargate.proto.QueryOuterClass.Value;
import io.stargate.proto.QueryOuterClass.Value.InnerCase;
import java.nio.ByteBuffer;

public class BytesCodec implements ValueCodec {
  @Override
  public ByteBuffer encode(@NonNull QueryOuterClass.Value value, @NonNull Column.ColumnType type)
      throws StatusException {
    if (value.getInnerCase() != InnerCase.BYTES) {
      throw Status.FAILED_PRECONDITION.withDescription("Expected bytes type").asException();
    }
    return ByteBuffer.wrap(value.getBytes().toByteArray());
  }

  @Override
  public QueryOuterClass.Value decode(@NonNull ByteBuffer bytes) {
    return Value.newBuilder().setBytes(ByteString.copyFrom(bytes)).build();
  }
}
