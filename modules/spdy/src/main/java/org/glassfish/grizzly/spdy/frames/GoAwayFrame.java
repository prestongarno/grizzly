/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2012 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package org.glassfish.grizzly.spdy.frames;

import org.glassfish.grizzly.Buffer;
import org.glassfish.grizzly.memory.MemoryManager;
import org.glassfish.grizzly.spdy.Constants;

public class GoAwayFrame extends SpdyFrame {

    private static final Marshaller MARSHALLER = new GoAwayFrameMarshaller();

    public static final int TYPE = 7;

    private int lastGoodStreamId;
    private int statusCode;


    // ------------------------------------------------------------ Constructors


    public GoAwayFrame() {
        super();
    }

    protected GoAwayFrame(SpdyHeader header) {
        super(header);
        lastGoodStreamId = header.buffer.getInt() & 0x7FFFFFFF;
        if (header.buffer.hasRemaining()) {
            statusCode = header.buffer.getInt();
        }
        header.buffer.dispose();
    }


    // ---------------------------------------------------------- Public Methods


    public int getLastGoodStreamId() {
        return lastGoodStreamId;
    }

    public void setLastGoodStreamId(int lastGoodStreamId) {
        if (header == null) {
            this.lastGoodStreamId = lastGoodStreamId;
        }
    }

    public int getStatusCode() {
        return statusCode;
    }

    public void setStatusCode(int statusCode) {
        if (header == null) {
            this.statusCode = statusCode;
        }
    }


    // -------------------------------------------------- Methods from SpdyFrame

    @Override
    public Marshaller getMarshaller() {
        return MARSHALLER;
    }


    // -------------------------------------------------- Methods from Cacheable


    @Override
    public void recycle() {
        statusCode = 0;
        lastGoodStreamId = 0;
        super.recycle();
    }


    // ---------------------------------------------------------- Nested Classes


    private static final class GoAwayFrameMarshaller implements Marshaller {

        @Override
        public Buffer marshall(final SpdyFrame frame, final MemoryManager memoryManager) {
            GoAwayFrame goAwayFrame = (GoAwayFrame) frame;
            final Buffer buffer = memoryManager.allocate(16);

            buffer.putInt(0x80000000 | (Constants.SPDY_VERSION << 16) | TYPE); // "C", version, GOAWAY_FRAME
            buffer.putInt(8); // Flags, Length
            buffer.putInt(goAwayFrame.lastGoodStreamId & 0x7FFFFFFF); // Stream-ID
            buffer.putInt(goAwayFrame.statusCode); // Status code
            buffer.trim();
            return buffer;

        }

    } // END GoAwayFrameMarshaller

}
