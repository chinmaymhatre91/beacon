/**
 * 
 */
package net.beaconcontroller.web.view.json;

import java.io.IOException;

import org.codehaus.jackson.JsonGenerator;
import org.codehaus.jackson.JsonProcessingException;
import org.codehaus.jackson.map.JsonSerializer;
import org.codehaus.jackson.map.SerializerProvider;
import org.openflow.util.U8;

/**
 * @author David Erickson (derickso@cs.stanford.edu)
 *
 */
public class UnsignedByteJsonSerializer extends JsonSerializer<Byte> {

    @Override
    public void serialize(Byte value, JsonGenerator jgen,
            SerializerProvider provider) throws IOException,
            JsonProcessingException {
        jgen.writeNumber(U8.f(value));
    }
}
