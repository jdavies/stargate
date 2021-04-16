package io.stargate.grpc.codec.cql;

import com.datastax.oss.driver.api.core.ProtocolVersion;
import com.datastax.oss.driver.api.core.type.codec.TypeCodec;
import edu.umd.cs.findbugs.annotations.NonNull;
import io.grpc.Status;
import io.grpc.StatusException;
import io.stargate.db.schema.Column;
import io.stargate.proto.QueryOuterClass;
import io.stargate.proto.QueryOuterClass.Value;
import io.stargate.proto.QueryOuterClass.Value.InnerCase;
import java.nio.ByteBuffer;

public class StringCodec implements ValueCodec {
  private TypeCodec<String> innerCodec;

  public StringCodec(@NonNull TypeCodec<String> innerCodec) {
    this.innerCodec = innerCodec;
  }

  @Override
  public ByteBuffer encode(@NonNull QueryOuterClass.Value value, @NonNull Column.ColumnType type)
      throws StatusException {
    if (value.getInnerCase() != InnerCase.STRING) {
      throw Status.FAILED_PRECONDITION.withDescription("Expected string type").asException();
    }
    return innerCodec.encode(value.getString(), ProtocolVersion.DEFAULT);
  }

  @Override
  public QueryOuterClass.Value decode(@NonNull ByteBuffer bytes) {
    return Value.newBuilder().setString(innerCodec.decode(bytes, ProtocolVersion.DEFAULT)).build();
  }
}