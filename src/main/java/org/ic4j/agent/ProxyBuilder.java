/*
 * Copyright 2021 Exilor Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/

package org.ic4j.agent;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Proxy;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.Security;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.ic4j.agent.annotations.Canister;
import org.ic4j.agent.annotations.EffectiveCanister;
import org.ic4j.agent.annotations.Transport;
import org.ic4j.agent.annotations.QUERY;
import org.ic4j.agent.annotations.UPDATE;
import org.ic4j.agent.http.ReplicaApacheHttpTransport;
import org.ic4j.agent.identity.AnonymousIdentity;
import org.ic4j.agent.identity.BasicIdentity;
import org.ic4j.agent.identity.Identity;
import org.ic4j.agent.identity.PemError;
import org.ic4j.agent.identity.Secp256k1Identity;
import org.ic4j.agent.annotations.Argument;
import org.ic4j.candid.annotations.Ignore;
import org.ic4j.candid.annotations.Name;
import org.ic4j.candid.parser.IDLArgs;
import org.ic4j.candid.parser.IDLType;
import org.ic4j.candid.parser.IDLValue;
import org.ic4j.candid.pojo.PojoDeserializer;
import org.ic4j.candid.pojo.PojoSerializer;
import org.ic4j.candid.types.Type;
import org.ic4j.types.Principal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ProxyBuilder {

	static final Logger LOG = LoggerFactory.getLogger(ProxyBuilder.class);

	static int WAITER_TIMEOUT = 60;
	static int WAITER_SLEEP = 5;

	Agent agent;
	Principal effectiveCanisterId;
	Principal canisterId;
	Optional<Long> ingressExpiryDatetime;

	private Waiter waiter;

	ProxyBuilder(Agent agent, Principal canisterId) {
		Security.addProvider(new BouncyCastleProvider());

		this.agent = agent;
		this.canisterId = canisterId;
		this.effectiveCanisterId = canisterId.clone();
		this.ingressExpiryDatetime = Optional.empty();
	}

	ProxyBuilder(Principal canisterId) {
		Security.addProvider(new BouncyCastleProvider());

		this.canisterId = canisterId;
		this.effectiveCanisterId = canisterId.clone();
		this.ingressExpiryDatetime = Optional.empty();
	}

	ProxyBuilder() {
		Security.addProvider(new BouncyCastleProvider());

		this.ingressExpiryDatetime = Optional.empty();
	}

	public static ProxyBuilder create(Agent agent, Principal canisterId) {
		return new ProxyBuilder(agent, canisterId);
	}

	public static ProxyBuilder create(Principal canisterId) {
		return new ProxyBuilder(canisterId);
	}

	public static ProxyBuilder create() {
		return new ProxyBuilder();
	}

	public ProxyBuilder effectiveCanisterId(Principal effectiveCanisterId) {
		this.effectiveCanisterId = effectiveCanisterId;
		return this;
	}

	/**
	 * Takes a SystemTime converts it to a Duration by calling
	 * duration_since(UNIX_EPOCH) to learn about where in time this SystemTime lies.
	 * The Duration is converted to nanoseconds and stored in ingressExpiryDatetime
	 */
	public ProxyBuilder expireAt(LocalDateTime time) {
		this.ingressExpiryDatetime = Optional.of(time.toEpochSecond(ZoneOffset.UTC));

		return this;
	}

	/**
	 * Takes a Duration (i.e. 30 sec/5 min 30 sec/1 h 30 min, etc.) and adds it to
	 * the Duration of the current SystemTime since the UNIX_EPOCH Subtracts a
	 * permitted drift from the sum to account for using system time and not block
	 * time. Converts the difference to nanoseconds and stores in
	 * ingressExpiryDatetime
	 */

	public ProxyBuilder expireAfter(Duration duration) {
		Duration permittedDrift = Duration.ofSeconds(Agent.DEFAULT_PERMITTED_DRIFT);

		this.ingressExpiryDatetime = Optional
				.of((Duration.ofMillis(System.currentTimeMillis()).plus(duration).minus(permittedDrift)).toNanos());

		return this;
	}

	public ProxyBuilder waiter(Waiter waiter) {
		this.waiter = waiter;

		return this;
	}

	public <T> T getProxy(Class<T> interfaceClass) {

		Agent agent = this.agent;

		if (agent == null) {
			if (interfaceClass.isAnnotationPresent(org.ic4j.agent.annotations.Agent.class)) {
				org.ic4j.agent.annotations.Agent agentAnnotation = interfaceClass
						.getAnnotation(org.ic4j.agent.annotations.Agent.class);

				Transport transportAnnotation = agentAnnotation.transport();
				Identity identity = new AnonymousIdentity();

				org.ic4j.agent.annotations.Identity identityAnnotation = agentAnnotation.identity();

				try {
					ReplicaTransport transport = ReplicaApacheHttpTransport.create(transportAnnotation.url());

					switch (identityAnnotation.type()) {
					case ANONYMOUS:
						identity = new AnonymousIdentity();
						break;
					case BASIC:
						if ("".equals(identityAnnotation.pem_file())) {
							KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
							identity = BasicIdentity.fromKeyPair(keyPair);
						} else {
							Path path = Paths.get(identityAnnotation.pem_file());

							identity = BasicIdentity.fromPEMFile(path);
						}
						break;
					case SECP256K1:
						Path path = Paths.get(identityAnnotation.pem_file());

						identity = Secp256k1Identity.fromPEMFile(path);
						break;

					}

					agent = new AgentBuilder().transport(transport).identity(identity).build();
				} catch (URISyntaxException e) {
					throw AgentError.create(AgentError.AgentErrorCode.INVALID_REPLICA_URL, e,
							transportAnnotation.url());
				} catch (NoSuchAlgorithmException e) {
					throw PemError.create(PemError.PemErrorCode.PEM_ERROR, e, identityAnnotation.pem_file());
				}
			} else
				throw AgentError.create(AgentError.AgentErrorCode.MISSING_REPLICA_TRANSPORT);
		}

		Principal canisterId = this.canisterId;
		Principal effectiveCanisterId = this.effectiveCanisterId;

		if (effectiveCanisterId == null) {
			if (interfaceClass.isAnnotationPresent(EffectiveCanister.class)) {
				EffectiveCanister effectiveCanister = interfaceClass.getAnnotation(EffectiveCanister.class);

				effectiveCanisterId = Principal.fromString(effectiveCanister.value());
			}
		}

		if (canisterId == null) {
			if (interfaceClass.isAnnotationPresent(Canister.class)) {
				Canister canister = interfaceClass.getAnnotation(Canister.class);

				canisterId = Principal.fromString(canister.value());

				if (effectiveCanisterId == null)
					effectiveCanisterId = canisterId.clone();
			}
		}

		AgentInvocationHandler agentInvocationHandler = new AgentInvocationHandler(agent, canisterId,
				effectiveCanisterId, this.ingressExpiryDatetime, waiter);
		T proxy = (T) Proxy.newProxyInstance(interfaceClass.getClassLoader(), new Class[] { interfaceClass },
				agentInvocationHandler);

		return proxy;
	}

	class AgentInvocationHandler implements InvocationHandler {
		Agent agent;
		Principal canisterId;
		Principal effectiveCanisterId;
		Optional<Long> ingressExpiryDatetime;
		Waiter waiter;

		AgentInvocationHandler(Agent agent, Principal canisterId, Principal effectiveCanisterId,
				Optional<Long> ingressExpiryDatetime, Waiter waiter) {
			this.agent = agent;
			this.canisterId = canisterId;
			this.effectiveCanisterId = effectiveCanisterId;
			this.ingressExpiryDatetime = ingressExpiryDatetime;
			this.waiter = waiter;
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {

			if (method.isAnnotationPresent(QUERY.class) || method.isAnnotationPresent(UPDATE.class)) {
				MethodType methodType = null;

				String methodName = method.getName();

				if (method.isAnnotationPresent(QUERY.class))
					methodType = MethodType.QUERY;
				else if (method.isAnnotationPresent(UPDATE.class))
					methodType = MethodType.UPDATE;

				if (method.isAnnotationPresent(Name.class)) {
					Name nameAnnotation = method.getAnnotation(Name.class);
					methodName = nameAnnotation.value();
				}
				
				Parameter[] parameters = method.getParameters();

				ArrayList<IDLValue> candidArgs = new ArrayList<IDLValue>();

				PojoSerializer pojoSerializer = new PojoSerializer();
				
				if (args != null)
					for (int i = 0; i < args.length; i++) {
						Object arg = args[i];
						Argument argumentAnnotation = null;

						boolean skip = false;
						
						for (Annotation annotation : method.getParameterAnnotations()[i]) {
							if(Ignore.class.isInstance(annotation))
							{
								skip = true;
								continue;
							}
							if (Argument.class.isInstance(annotation)) 
								argumentAnnotation = (Argument) annotation;
						}
						
						if(skip) 
							continue;
						
						{
							if (argumentAnnotation != null) {
								Type type = argumentAnnotation.value();
								
								IDLType idlType;
								
								if(parameters[i].getType().isArray())
									idlType = IDLType.createType(Type.VEC,IDLType.createType(type));
								else
									idlType = IDLType.createType(type);
								
								IDLValue idlValue = IDLValue.create(arg,pojoSerializer, idlType);	
								
								candidArgs.add(idlValue);
							} else
								candidArgs.add(IDLValue.create(arg, pojoSerializer));
						}							
					}

				IDLArgs idlArgs = IDLArgs.create(candidArgs);

				byte[] buf = idlArgs.toBytes();

				switch (methodType) {
				case QUERY: {
					QueryBuilder queryBuilder = QueryBuilder.create(agent, this.canisterId, methodName);

					queryBuilder.effectiveCanisterId = this.effectiveCanisterId;
					queryBuilder.ingressExpiryDatetime = this.ingressExpiryDatetime;

					CompletableFuture<byte[]> builderResponse = queryBuilder.arg(buf).call();

					PojoDeserializer pojoDeserializer = new PojoDeserializer();
					try {
						if (method.getReturnType().equals(CompletableFuture.class)) {
							CompletableFuture<?> response = new CompletableFuture();

							builderResponse.whenComplete((input, ex) -> {
								if (ex == null) {
									if (input != null) {
										IDLArgs outArgs = IDLArgs.fromBytes(input);

										if (outArgs.getArgs().isEmpty())
											response.completeExceptionally(AgentError.create(
													AgentError.AgentErrorCode.CUSTOM_ERROR, "Missing return value"));
										else
											response.complete(outArgs.getArgs().get(0).getValue());
									} else
										response.completeExceptionally(AgentError.create(
												AgentError.AgentErrorCode.CUSTOM_ERROR, "Missing return value"));
								} else
									response.completeExceptionally(ex);
							});
							return response;
						} else {
							byte[] output = builderResponse.get();

							IDLArgs outArgs = IDLArgs.fromBytes(output);

							if (outArgs.getArgs().isEmpty())
								throw AgentError.create(AgentError.AgentErrorCode.CUSTOM_ERROR, "Missing return value");

							return outArgs.getArgs().get(0).getValue(pojoDeserializer,method.getReturnType());
						}

					} catch (Exception e) {
						throw AgentError.create(AgentError.AgentErrorCode.CUSTOM_ERROR, e, e.getLocalizedMessage());
					}
				}
				case UPDATE: {
					UpdateBuilder updateBuilder = UpdateBuilder.create(this.agent, this.canisterId, methodName);

					updateBuilder.effectiveCanisterId = this.effectiveCanisterId;
					updateBuilder.ingressExpiryDatetime = this.ingressExpiryDatetime;

					CompletableFuture<?> response = new CompletableFuture();

					Waiter waiter = this.waiter;

					if (waiter == null) {
						if (method.isAnnotationPresent(org.ic4j.agent.annotations.Waiter.class)) {
							org.ic4j.agent.annotations.Waiter waiterAnnotation = method
									.getAnnotation(org.ic4j.agent.annotations.Waiter.class);
							waiter = Waiter.create(waiterAnnotation.timeout(), waiterAnnotation.sleep());
						} else
							waiter = Waiter.create(WAITER_TIMEOUT, WAITER_SLEEP);
					}

					CompletableFuture<byte[]> builderResponse = updateBuilder.arg(buf).callAndWait(waiter);

					builderResponse.whenComplete((input, ex) -> {
						if (ex == null) {
							if (input != null) {
								IDLArgs outArgs = IDLArgs.fromBytes(input);

								if (outArgs.getArgs().isEmpty())
									response.completeExceptionally(AgentError
											.create(AgentError.AgentErrorCode.CUSTOM_ERROR, "Missing return value"));
								else
									response.complete(outArgs.getArgs().get(0).getValue());
							} else
								response.completeExceptionally(AgentError.create(AgentError.AgentErrorCode.CUSTOM_ERROR,
										"Missing return value"));
						} else
							response.completeExceptionally(ex);
					});
					return response;
				}
				default:
					throw AgentError.create(AgentError.AgentErrorCode.CUSTOM_ERROR, "Invalid Candid method type");
				}
			} else
				throw AgentError.create(AgentError.AgentErrorCode.CUSTOM_ERROR, "Candid method type not defined");
		}

	}

}
