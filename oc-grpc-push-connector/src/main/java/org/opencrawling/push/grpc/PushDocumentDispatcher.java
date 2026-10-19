/*
 * Copyright © ${year} the original author or authors (piergiorgio@apache.org)
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
package org.opencrawling.push.grpc;

import org.opencrawling.push.v1.PushDocumentRequest;
import org.opencrawling.push.v1.PushDocumentResponse;

/**
 * Strategy interface for dispatching pushed OIS documents into the OpenCrawling ingestion pipeline.
 */
public interface PushDocumentDispatcher {

    /**
     * Dispatches an incoming PushDocumentRequest to the processing pipeline.
     *
     * @param request the gRPC push request
     * @return response indicating acceptance, queueing, or rejection
     */
    PushDocumentResponse dispatch(PushDocumentRequest request);
}
