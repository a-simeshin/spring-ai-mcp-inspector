/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * Context-shutdown plumbing shared by both starters.
 *
 * <p>
 * Everything here runs on the thread that calls {@code context.close()}, during
 * {@link org.springframework.context.event.ContextClosedEvent} — i.e. before Boot's
 * {@code WebServerGracefulShutdownLifecycle} starts waiting for in-flight requests. Types
 * in this package are public only because both starter modules call them; they are
 * internal plumbing, not API, and may change in any release.
 *
 * @author Artem Simeshin
 */
package io.inspector.mcp.core.shutdown;
