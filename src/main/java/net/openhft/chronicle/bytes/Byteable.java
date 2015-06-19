/*
 *     Copyright (C) 2015  higherfrequencytrading.com
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU Lesser General Public License as published by
 *     the Free Software Foundation, either version 3 of the License.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU Lesser General Public License for more details.
 *
 *     You should have received a copy of the GNU Lesser General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.openhft.chronicle.bytes;

/**
 * User: peter.lawrey
 * Date: 07/10/13
 * Time: 21:38
 */
public interface Byteable<Underlying> {
    /**
     * This setter for a data type which points to an underlying ByteStore.
     *
     * @param bytesStore the fix point ByteStore
     * @param offset the offset within the ByteStore
     * @param length the length in the ByteStore
     */
    void bytesStore(BytesStore<Bytes<Underlying>, Underlying> bytesStore, long offset, long length);

    /**
     * @return the underlying ByteStore
     */
    BytesStore<Bytes<Underlying>, Underlying> bytesStore();

    long offset();

    long maxSize();
}