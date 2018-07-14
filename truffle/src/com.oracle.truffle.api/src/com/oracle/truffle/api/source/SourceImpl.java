/*
 * Copyright (c) 2013, 2018, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.truffle.api.source;

import java.net.URI;
import java.net.URL;
import java.util.Objects;

final class SourceImpl extends Source {

    private final Key key;
    private final Object sourceId;

    private SourceImpl(Key key) {
        this.key = key;
        /*
         * SourceImpl instances are interned so a single instance can identify it. We cannot use
         * SourceImpl directly as the sourceId needs to be shared when a source is cloned.
         */
        this.sourceId = new SourceId(key.hashCode());
    }

    private SourceImpl(Key key, Object sourceId) {
        this.key = key;
        this.sourceId = sourceId;
    }

    @Override
    protected Object getSourceId() {
        return sourceId;
    }

    @Override
    public CharSequence getCharacters() {
        return key.characters;
    }

    @Override
    Source copy() {
        return new SourceImpl(key, sourceId);
    }

    @Override
    public boolean isCached() {
        return key.cached;
    }

    @Override
    public String getName() {
        return key.name;
    }

    @Override
    public String getPath() {
        return key.path;
    }

    @Override
    public boolean isInternal() {
        return key.internal;
    }

    @Override
    public boolean isInteractive() {
        return key.interactive;
    }

    @Override
    public URL getURL() {
        return key.url;
    }

    @Override
    public URI getOriginalURI() {
        return key.uri;
    }

    @Override
    public String getMimeType() {
        return key.mimeType;
    }

    @Override
    public String getLanguage() {
        return key.language;
    }

    Key toKey() {
        return key;
    }

    private static final class SourceId {

        /*
         * We store the hash of the key to have stable source hashCode for each run.
         */
        final int hash;

        SourceId(int hash) {
            this.hash = hash;
        }

        @Override
        public boolean equals(Object obj) {
            return this == obj;
        }

        @Override
        public int hashCode() {
            return hash;
        }

    }

    static final class Key {

        final CharSequence characters;
        final URI uri;
        final URL url;
        final String name;
        final String mimeType;
        final String language;
        final String path;
        final boolean internal;
        final boolean interactive;
        final boolean cached;

        Key(CharSequence characters, String mimeType, String languageId, URL url, URI uri, String name, String path, boolean internal, boolean interactive, boolean cached) {
            this.characters = characters;
            this.mimeType = mimeType;
            this.language = languageId;
            this.name = name;
            this.path = path;
            this.internal = internal;
            this.interactive = interactive;
            this.cached = cached;
            this.url = url;
            this.uri = uri;
        }

        @Override
        public int hashCode() {
            int result = 31 * 1 + ((characters == null) ? 0 : characters.hashCode());
            result = 31 * result + (interactive ? 1231 : 1237);
            result = 31 * result + (internal ? 1231 : 1237);
            result = 31 * result + (cached ? 1231 : 1237);
            result = 31 * result + ((language == null) ? 0 : language.hashCode());
            result = 31 * result + ((mimeType == null) ? 0 : mimeType.hashCode());
            result = 31 * result + ((name == null) ? 0 : name.hashCode());
            result = 31 * result + ((path == null) ? 0 : path.hashCode());
            result = 31 * result + ((uri == null) ? 0 : uri.hashCode());
            result = 31 * result + ((url == null) ? 0 : url.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            } else if (!(obj instanceof Key)) {
                return false;
            }
            assert characters != null;
            Key other = (Key) obj;
            /*
             * Compare characters last as it is likely the most expensive comparison in the worst
             * case.
             */
            return Objects.equals(language, other.language) && //
                            Objects.equals(mimeType, other.mimeType) && //
                            Objects.equals(name, other.name) && //
                            Objects.equals(path, other.path) && //
                            Objects.equals(uri, other.uri) && //
                            Objects.equals(url, other.url) && //
                            interactive == other.interactive && //
                            internal == other.internal &&
                            cached == other.cached &&
                            compareCharacters(other);
        }

        private boolean compareCharacters(Key other) {
            CharSequence otherCharacters = other.characters;
            if (characters == otherCharacters) {
                return true;
            } else if (characters == null) {
                return false;
            } else if (characters.length() != otherCharacters.length()) {
                return false;
            } else {
                assert otherCharacters != null;
                return Objects.equals(characters.toString(), otherCharacters.toString());
            }
        }

        SourceImpl toSource() {
            return new SourceImpl(this);
        }

    }

}
