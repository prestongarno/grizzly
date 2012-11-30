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
import org.glassfish.grizzly.Cacheable;
import org.glassfish.grizzly.memory.Buffers;
import org.glassfish.grizzly.memory.MemoryManager;

public abstract class SpdyFrame implements Cacheable {

    protected SpdyHeader header;
    protected boolean last;


    // ------------------------------------------------------------ Constructors


    protected SpdyFrame() { }

    protected SpdyFrame(final SpdyHeader header) {
        this.header = header;
    }


    // ---------------------------------------------------------- Public Methods


    public static SpdyFrame wrap(final Buffer buffer) {
        SpdyHeader header = new SpdyHeader();
        header.initialize(buffer);
        if (header.control) {
            switch (header.type) {
                case SynStreamFrame.TYPE:
                    return new SynStreamFrame(header);
                case SynReplyFrame.TYPE:
                    return new SynReplyFrame(header);
                case RstStreamFrame.TYPE:
                    return new RstStreamFrame(header);
                case SettingsFrame.TYPE:
                    return new SettingsFrame(header);
                case PingFrame.TYPE:
                    return new PingFrame(header);
                case GoAwayFrame.TYPE:
                    return new GoAwayFrame(header);
                case HeadersFrame.TYPE:
                    return new HeadersFrame(header);
                case WindowUpdateFrame.TYPE:
                    return new WindowUpdateFrame(header);
                case CredentialFrame.TYPE:
                    return new CredentialFrame(header);
                default:
                    throw new IllegalStateException("Unhandled control frame");

            }
        } else {
            return new DataFrame(header);
        }
    }

    public boolean isLast() {
        return last;
    }

    public void setLast(boolean last) {
        if (header == null) {
            this.last = last;
        }
    }

    public SpdyHeader getHeader() {
        return header;
    }

    public abstract Marshaller getMarshaller();


    // -------------------------------------------------- Methods from Cacheable


    @Override
    public void recycle() {
        header.recycle();
    }


    // ------------------------------------------------------- Protected Methods


    protected static Buffer allocateHeapBuffer(final MemoryManager mm, final int size) {
        if (!mm.willAllocateDirect(size)) {
            return mm.allocateAtLeast(size);
        } else {
            return Buffers.wrap(mm, new byte[size]);
        }
    }


    // ---------------------------------------------------------- Nested Classes


    public interface Marshaller {

        Buffer marshall(final SpdyFrame frame, final MemoryManager memoryManager);

    } // END Marshaller
}
