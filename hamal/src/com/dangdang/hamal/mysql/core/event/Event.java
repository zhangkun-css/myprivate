/*
 *  
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dangdang.hamal.mysql.core.event;

import java.io.Serializable;

/**
 * @author  
 */
public class Event implements Serializable {

	private long spoutTs;
	
    private EventHeader header;
    private EventData data;

    public Event(EventHeader header, EventData data) {
    	spoutTs=System.currentTimeMillis();
        this.header = header;
        this.data = data;
    }

    public long getSpoutTs() {
		return spoutTs;
	}

	@SuppressWarnings("unchecked")
    public <T extends EventHeader> T getHeader() {
        return (T) header;
    }

    @SuppressWarnings("unchecked")
    public <T extends EventData> T getData() {
        return (T) data;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        sb.append("Event");
        sb.append("{header=").append(header);
        sb.append(", data=").append(data);
        sb.append('}');
        return sb.toString();
    }
}
