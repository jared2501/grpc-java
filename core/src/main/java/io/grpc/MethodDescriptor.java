/*
 * Copyright 2014, gRPC Authors All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.base.Preconditions;
import io.opencensus.trace.Tracing;
import io.opencensus.trace.export.SampledSpanStore;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReferenceArray;
import javax.annotation.CheckReturnValue;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

/**
 * Description of a remote method used by {@link Channel} to initiate a call.
 *
 * <p>Provides the name of the operation to execute as well as {@link Marshaller} instances
 * used to parse and serialize request and response messages.
 *
 * <p>Can be constructed manually but will often be generated by stub code generators.
 *
 * @since 1.0.0
 */
@Immutable
public final class MethodDescriptor<ReqT, RespT> {

  private final MethodType type;
  private final String fullMethodName;
  private final Marshaller<ReqT> requestMarshaller;
  private final Marshaller<RespT> responseMarshaller;
  private final @Nullable Object schemaDescriptor;
  private final boolean idempotent;
  private final boolean safe;

  // Must be set to InternalKnownTransport.values().length
  // Not referenced to break the dependency.
  private final AtomicReferenceArray<Object> rawMethodNames = new AtomicReferenceArray<Object>(1);

  /**
   * Gets the cached "raw" method name for this Method Descriptor.  The raw name is transport
   * specific, and should be set using {@link #setRawMethodName} by the transport.
   *
   * @param transportOrdinal the unique ID of the transport, given by
   *        {@link InternalKnownTransport#ordinal}.
   * @return a transport specific representation of the method name.
   */
  final Object getRawMethodName(int transportOrdinal) {
    return rawMethodNames.get(transportOrdinal);
  }

  /**
   * Safely, but weakly, sets the raw method name for this Method Descriptor.  This should only be
   * called by the transport.  See {@link #getRawMethodName} for more detail.
   */
  final void setRawMethodName(int transportOrdinal, Object o) {
    rawMethodNames.lazySet(transportOrdinal, o);
  }
  
  /**
   * Convert a full method name to a tracing span name.
   *
   * @param isServer {@code false} if the span is on the client-side, {@code true} if on the
   *                 server-side
   * @param fullMethodName the method name as returned by {@link #getFullMethodName}.
   */
  static String generateTraceSpanName(boolean isServer, String fullMethodName) {
    String prefix = isServer ? "Recv" : "Sent";
    return prefix + "." + fullMethodName.replace('/', '.');
  }

  /**
   * The call type of a method.
   *
   * @since 1.0.0
   */
  public enum MethodType {
    /**
     * One request message followed by one response message.
     */
    UNARY,

    /**
     * Zero or more request messages followed by one response message.
     */
    CLIENT_STREAMING,

    /**
     * One request message followed by zero or more response messages.
     */
    SERVER_STREAMING,

    /**
     * Zero or more request and response messages arbitrarily interleaved in time.
     */
    BIDI_STREAMING,

    /**
     * Cardinality and temporal relationships are not known. Implementations should not make
     * buffering assumptions and should largely treat the same as {@link #BIDI_STREAMING}.
     */
    UNKNOWN;

    /**
     * Returns {@code true} if the client will immediately send one request message to the server
     * after calling {@link ClientCall#start(io.grpc.ClientCall.Listener, io.grpc.Metadata)}
     * and then immediately half-close the stream by calling {@link io.grpc.ClientCall#halfClose()}.
     *
     * @since 1.0.0
     */
    public final boolean clientSendsOneMessage() {
      return this == UNARY || this == SERVER_STREAMING;
    }

    /**
     * Returns {@code true} if the server will immediately send one response message to the client
     * upon receipt of {@link io.grpc.ServerCall.Listener#onHalfClose()} and then immediately
     * close the stream by calling {@link ServerCall#close(Status, io.grpc.Metadata)}.
     *
     * @since 1.0.0
     */
    public final boolean serverSendsOneMessage() {
      return this == UNARY || this == CLIENT_STREAMING;
    }
  }

  /**
   * A typed abstraction over message serialization and deserialization, a.k.a. marshalling and
   * unmarshalling.
   *
   * <p>Stub implementations will define implementations of this interface for each of the request
   * and response messages provided by a service.
   *
   * @param <T> type of serializable message
   * @since 1.0.0
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1777")
  public interface Marshaller<T> {
    /**
     * Given a message, produce an {@link InputStream} for it so that it can be written to the wire.
     * Where possible implementations should produce streams that are {@link io.grpc.KnownLength}
     * to improve transport efficiency.
     *
     * @param value to serialize.
     * @return serialized value as stream of bytes.
     */
    public InputStream stream(T value);

    /**
     * Given an {@link InputStream} parse it into an instance of the declared type so that it can be
     * passed to application code.
     *
     * @param stream of bytes for serialized value
     * @return parsed value
     */
    public T parse(InputStream stream);
  }

  /**
   * A marshaller that supports retrieving it's type parameter {@code T} at runtime.
   *
   * @since 1.1.0
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/2222")
  public interface ReflectableMarshaller<T> extends Marshaller<T> {
    /**
     * Returns the {@code Class} that this marshaller serializes and deserializes. If inheritance is
     * allowed, this is the base class or interface for all supported classes.
     *
     * @return non-{@code null} base class for all objects produced and consumed by this marshaller
     */
    public Class<T> getMessageClass();
  }

  /**
   * A marshaller that uses a fixed instance of the type it produces.
   *
   * @since 1.1.0
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/2222")
  public interface PrototypeMarshaller<T> extends ReflectableMarshaller<T> {
    /**
     * An instance of the expected message type, typically used as a schema and helper for producing
     * other message instances. The {@code null} value may be a special value for the marshaller
     * (like the equivalent of {@link Void}), so it is a valid return value. {@code null} does
     * <em>not</em> mean "unsupported" or "unknown".
     *
     * <p>It is generally expected this would return the same instance each invocation, but it is
     * not a requirement.
     */
    @Nullable
    public T getMessagePrototype();
  }

  /**
   * Creates a new {@code MethodDescriptor}.
   *
   * @param type the call type of this method
   * @param fullMethodName the fully qualified name of this method
   * @param requestMarshaller the marshaller used to encode and decode requests
   * @param responseMarshaller the marshaller used to encode and decode responses
   * @since 1.0.0
   * @deprecated use {@link #newBuilder()}.
   */
  @Deprecated
  public static <RequestT, ResponseT> MethodDescriptor<RequestT, ResponseT> create(
      MethodType type, String fullMethodName,
      Marshaller<RequestT> requestMarshaller,
      Marshaller<ResponseT> responseMarshaller) {
    return new MethodDescriptor<RequestT, ResponseT>(
        type, fullMethodName, requestMarshaller, responseMarshaller, null, false, false);
  }

  private MethodDescriptor(
      MethodType type,
      String fullMethodName,
      Marshaller<ReqT> requestMarshaller,
      Marshaller<RespT> responseMarshaller,
      Object schemaDescriptor,
      boolean idempotent,
      boolean safe) {

    this.type = Preconditions.checkNotNull(type, "type");
    this.fullMethodName = Preconditions.checkNotNull(fullMethodName, "fullMethodName");
    this.requestMarshaller = Preconditions.checkNotNull(requestMarshaller, "requestMarshaller");
    this.responseMarshaller = Preconditions.checkNotNull(responseMarshaller, "responseMarshaller");
    this.schemaDescriptor = schemaDescriptor;
    this.idempotent = idempotent;
    this.safe = safe;
    Preconditions.checkArgument(!safe || type == MethodType.UNARY,
        "Only unary methods can be specified safe");
  }

  /**
   * The call type of the method.
   *
   * @since 1.0.0
   */
  public MethodType getType() {
    return type;
  }

  /**
   * The fully qualified name of the method.
   *
   * @since 1.0.0
   */
  public String getFullMethodName() {
    return fullMethodName;
  }

  /**
   * Parse a response payload from the given {@link InputStream}.
   *
   * @param input stream containing response message to parse.
   * @return parsed response message object.
   * @since 1.0.0
   */
  public RespT parseResponse(InputStream input) {
    return responseMarshaller.parse(input);
  }

  /**
   * Convert a request message to an {@link InputStream}.
   *
   * @param requestMessage to serialize using the request {@link Marshaller}.
   * @return serialized request message.
   * @since 1.0.0
   */
  public InputStream streamRequest(ReqT requestMessage) {
    return requestMarshaller.stream(requestMessage);
  }

  /**
   * Parse an incoming request message.
   *
   * @param input the serialized message as a byte stream.
   * @return a parsed instance of the message.
   * @since 1.0.0
   */
  public ReqT parseRequest(InputStream input) {
    return requestMarshaller.parse(input);
  }

  /**
   * Serialize an outgoing response message.
   *
   * @param response the response message to serialize.
   * @return the serialized message as a byte stream.
   * @since 1.0.0
   */
  public InputStream streamResponse(RespT response) {
    return responseMarshaller.stream(response);
  }

  /**
   * Returns the marshaller for the request type. Allows introspection of the request marshaller.
   *
   * @since 1.1.0
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/2592")
  public Marshaller<ReqT> getRequestMarshaller() {
    return requestMarshaller;
  }

  /**
   * Returns the marshaller for the response type. Allows introspection of the response marshaller.
   *
   * @since 1.1.0
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/2592")
  public Marshaller<RespT> getResponseMarshaller() {
    return responseMarshaller;
  }

  /**
   * Returns the schema descriptor for this method. A schema descriptor is an object that is not
   * used by gRPC core but includes information related to the service method. The type of the
   * object is specific to the consumer, so both the code setting the schema descriptor and the code
   * calling {@link #getSchemaDescriptor()} must coordinate.  For example, protobuf generated code
   * sets this value, in order to be consumed by the server reflection service.  See also:
   * {@code io.grpc.protobuf.ProtoMethodDescriptorSupplier}.
   *
   * @since 1.7.0
   */
  public @Nullable Object getSchemaDescriptor() {
    return schemaDescriptor;
  }

  /**
   * Returns whether this method is idempotent.
   *
   * @since 1.0.0
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1775")
  public boolean isIdempotent() {
    return idempotent;
  }

  /**
   * Returns whether this method is safe.
   *
   * <p>A safe request does nothing except retrieval so it has no side effects on the server side.
   *
   * @since 1.1.0
   */
  @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1775")
  public boolean isSafe() {
    return safe;
  }

  /**
   * Generate the fully qualified method name.
   *
   * @param fullServiceName the fully qualified service name that is prefixed with the package name
   * @param methodName the short method name
   * @since 1.0.0
   */
  public static String generateFullMethodName(String fullServiceName, String methodName) {
    return checkNotNull(fullServiceName, "fullServiceName")
        + "/"
        + checkNotNull(methodName, "methodName");
  }

  /**
   * Extract the fully qualified service name out of a fully qualified method name. May return
   * {@code null} if the input is malformed, but you cannot rely on it for the validity of the
   * input.
   *
   * @since 1.0.0
   */
  @Nullable
  public static String extractFullServiceName(String fullMethodName) {
    int index = checkNotNull(fullMethodName, "fullMethodName").lastIndexOf('/');
    if (index == -1) {
      return null;
    }
    return fullMethodName.substring(0, index);
  }

  /**
   * Creates a new builder for a {@link MethodDescriptor}.
   *
   * @since 1.1.0
   */
  @CheckReturnValue
  public static <ReqT, RespT> Builder<ReqT, RespT> newBuilder() {
    return newBuilder(null, null);
  }

  /**
   * Creates a new builder for a {@link MethodDescriptor}.
   *
   * @since 1.1.0
   */
  @CheckReturnValue
  public static <ReqT, RespT> Builder<ReqT, RespT> newBuilder(
      Marshaller<ReqT> requestMarshaller, Marshaller<RespT> responseMarshaller) {
    return new Builder<ReqT, RespT>()
        .setRequestMarshaller(requestMarshaller)
        .setResponseMarshaller(responseMarshaller);
  }

  /**
   * Turns this descriptor into a builder.
   *
   * @since 1.1.0
   */
  @CheckReturnValue
  public Builder<ReqT, RespT> toBuilder() {
    return toBuilder(requestMarshaller, responseMarshaller);
  }

  /**
   * Turns this descriptor into a builder, replacing the request and response marshallers.
   *
   * @since 1.1.0
   */
  @CheckReturnValue
  public <NewReqT, NewRespT> Builder<NewReqT, NewRespT> toBuilder(
      Marshaller<NewReqT> requestMarshaller, Marshaller<NewRespT> responseMarshaller) {
    return MethodDescriptor.<NewReqT, NewRespT>newBuilder()
        .setRequestMarshaller(requestMarshaller)
        .setResponseMarshaller(responseMarshaller)
        .setType(type)
        .setFullMethodName(fullMethodName)
        .setIdempotent(idempotent)
        .setSafe(safe);
  }

  /**
   * A builder for a {@link MethodDescriptor}.
   *
   * @since 1.1.0
   */
  public static final class Builder<ReqT, RespT> {

    private Marshaller<ReqT> requestMarshaller;
    private Marshaller<RespT> responseMarshaller;
    private MethodType type;
    private String fullMethodName;
    private boolean idempotent;
    private boolean safe;
    private Object schemaDescriptor;
    private boolean registerForTracing;

    private Builder() {}

    /**
     * Sets the request marshaller.
     *
     * @param requestMarshaller the marshaller to use.
     * @since 1.1.0
     */
    public Builder<ReqT, RespT> setRequestMarshaller(Marshaller<ReqT> requestMarshaller) {
      this.requestMarshaller = requestMarshaller;
      return this;
    }

    /**
     * Sets the response marshaller.
     *
     * @param responseMarshaller the marshaller to use.
     * @since 1.1.0
     */
    @SuppressWarnings("unchecked")
    public Builder<ReqT, RespT> setResponseMarshaller(Marshaller<RespT> responseMarshaller) {
      this.responseMarshaller = responseMarshaller;
      return this;
    }

    /**
     * Sets the method type.
     *
     * @param type the type of the method.
     * @since 1.1.0
     */
    public Builder<ReqT, RespT> setType(MethodType type) {
      this.type = type;
      return this;
    }

    /**
     * Sets the fully qualified (service and method) method name.
     *
     * @see MethodDescriptor#generateFullMethodName
     * @since 1.1.0
     */
    public Builder<ReqT, RespT> setFullMethodName(String fullMethodName) {
      this.fullMethodName = fullMethodName;
      return this;
    }

    /**
     * Sets the schema descriptor for this builder.  A schema descriptor is an object that is not
     * used by gRPC core but includes information related to the methods. The type of the object
     * is specific to the consumer, so both the code calling this and the code calling
     * {@link MethodDescriptor#getSchemaDescriptor()} must coordinate.
     *
     * @param schemaDescriptor an object that describes the service structure.  Should be immutable.
     * @since 1.7.0
     */
    public Builder<ReqT, RespT> setSchemaDescriptor(@Nullable Object schemaDescriptor) {
      this.schemaDescriptor = schemaDescriptor;
      return this;
    }

    /**
     * Sets whether the method is idempotent.  If true, calling this method more than once doesn't
     * have additional side effects.
     *
     * @since 1.1.0
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1775")
    public Builder<ReqT, RespT> setIdempotent(boolean idempotent) {
      this.idempotent = idempotent;
      return this;
    }

    /**
     * Sets whether this method is safe.  If true, calling this method any number of times doesn't
     * have side effects.
     *
     * @since 1.1.0
     */
    @ExperimentalApi("https://github.com/grpc/grpc-java/issues/1775")
    public Builder<ReqT, RespT> setSafe(boolean safe) {
      this.safe = safe;
      return this;
    }

    /**
     * Sets whether the new MethodDescriptor should be registered into the tracing system, so that
     * RPCs on this method may be collected and stored.
     *
     * @since 1.7.0
     */
    public Builder<ReqT, RespT> setRegisterForTracing(boolean value) {
      this.registerForTracing = value;
      return this;
    }

    /**
     * Builds the method descriptor.
     *
     * @since 1.1.0
     */
    @CheckReturnValue
    public MethodDescriptor<ReqT, RespT> build() {
      if (registerForTracing) {
        SampledSpanStore sampledStore = Tracing.getExportComponent().getSampledSpanStore();
        if (sampledStore != null) {
          ArrayList<String> spanNames = new ArrayList<String>(2);
          spanNames.add(generateTraceSpanName(false, fullMethodName));
          spanNames.add(generateTraceSpanName(true, fullMethodName));
          sampledStore.registerSpanNamesForCollection(spanNames);
        }
      }
      return new MethodDescriptor<ReqT, RespT>(
          type,
          fullMethodName,
          requestMarshaller,
          responseMarshaller,
          schemaDescriptor,
          idempotent,
          safe);
    }
  }
}
