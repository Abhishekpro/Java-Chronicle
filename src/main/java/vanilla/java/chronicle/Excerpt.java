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

package vanilla.java.chronicle;

/**
 * An extracted record within a Chronicle.  This record refers to one entry.
 *
 * @author peter.lawrey
 */
public interface Excerpt extends RandomDataInput, RandomDataOutput {
    /**
     * @return the chronicle this is an excerpt for.
     */
    Chronicle chronicle();

    /**
     * Attempt to set the index to this number.  The method is re-tryable as another thread or process could be writing to this Chronicle.
     *
     * @param index within the Chronicle
     * @return true if the index could be set to a valid entry.
     * @throws IndexOutOfBoundsException If index < 0
     */
    boolean index(long index) throws IndexOutOfBoundsException;

    /**
     * @return the index of a valid entry or -1 if the index has never been set.
     */
    long index();

    /**
     * Set the position within this except.
     *
     * @param position to move to.
     * @return this
     */
    Excerpt position(int position);

    /**
     * @return the position within this excerpt
     */
    int position();

    /**
     * Change the type of the excerpt
     *
     * @param type of except to change this excerpt to.
     */
    void type(short type);

    /**
     * @return the type of excerpt.
     */
    short type();

    /**
     * @return the capacity of the excerpt.
     */
    int capacity();

    /**
     * Start a new excerpt in the Chronicle.
     *
     * @param type     of excerpt
     * @param capacity minimum capacity to allow for.
     */
    void startExcerpt(short type, int capacity);

    /**
     * Finish a record.  The record is not available until this is called.
     * <p/>
     * When the method is called the first time, the excerpt is shrink wrapped to the size actually used. i.e. where the position is.
     */
    void finish();
}
