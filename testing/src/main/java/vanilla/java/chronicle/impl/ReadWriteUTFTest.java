/*
 * Copyright 2011 Peter Lawrey
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package vanilla.java.chronicle.impl;

import org.junit.Test;
import vanilla.java.chronicle.Excerpt;

import java.io.IOException;

import static junit.framework.Assert.assertEquals;
import static vanilla.java.chronicle.impl.GlobalSettings.*;

/**
 * @author plawrey
 */
public class ReadWriteUTFTest {
    @Test
    public void testReadWriteUTF() throws IOException {
        final String basePath = BASE_DIR + "text";
        deleteOnExit(basePath);

        IndexedChronicle tsc = new IndexedChronicle(basePath, DATA_BIT_SIZE_HINT);
        tsc.useUnsafe(USE_UNSAFE);
        tsc.clear();
        Excerpt<IndexedChronicle> excerpt = tsc.createExcerpt();

        StringBuilder sb = new StringBuilder();
        for (int i = Character.MIN_CODE_POINT; i <= Character.MAX_CODE_POINT; i += 16) {
            sb.setLength(0);
            for (int j = i; j < i + 16; j++) {
                if (Character.isValidCodePoint(j))
                    sb.appendCodePoint(j);
            }
            excerpt.startExcerpt(2 + sb.length() * 3);
            excerpt.writeUTF(sb);
            excerpt.finish();
        }

        for (int i = Character.MIN_CODE_POINT, n = 0; i <= Character.MAX_CODE_POINT; i += 16, n++) {
            sb.setLength(0);
            for (int j = i; j < i + 16; j++) {
                if (Character.isValidCodePoint(j))
                    sb.appendCodePoint(j);
            }
            excerpt.index(n);
            String text = excerpt.readUTF();
            excerpt.finish();
            assertEquals("i: " + i, sb.toString(), text);
        }
    }
}
