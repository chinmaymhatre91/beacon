package net.beaconcontroller.web.view.json;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.codehaus.jackson.JsonEncoding;
import org.codehaus.jackson.JsonFactory;
import org.codehaus.jackson.JsonGenerator;
import org.springframework.web.servlet.view.AbstractView;

/**
 *
 * @author David Erickson (daviderickson@cs.stanford.edu)
 */
public class DataTableJsonView<T> extends AbstractView {
    /**
     * Interface used to format each row of data that will be output
     * to the DataTable
     *
     * @author David Erickson (daviderickson@cs.stanford.edu)
     * @param <T>
     */
    public interface DataTableFormatCallback<T> {

        /**
         * Called once for each row of data. Array start and end elements
         * automatically called before and after each row
         * @param data the data element in the collection
         * @param jg the generator
         * @throws IOException 
         */
        public void format(T data, JsonGenerator jg) throws IOException;
    }
    
    protected DataTableFormatCallback<T> cb;
    protected Collection<T> data;

    /**
     * Constructs this view with the corresponding data and formatting callback
     * @param data data used to populate the table
     * @param cb callback used for formatting and order of serializing the columns
     */
    public DataTableJsonView(Collection<T> data, DataTableFormatCallback<T> cb) {
        this.cb = cb;
        this.data = data;
    }

    @Override
    protected void renderMergedOutputModel(Map<String, Object> model,
            HttpServletRequest request, HttpServletResponse response) throws Exception {
        JsonFactory jsonFactory = new JsonFactory(); 
        JsonGenerator jg = jsonFactory.createJsonGenerator(response.getOutputStream(), JsonEncoding.UTF8);
        jg.writeStartObject();
        jg.writeArrayFieldStart("aaData");
        for (T t : data) {
            jg.writeStartArray();
            cb.format(t, jg);
            jg.writeEndArray();
        }
        jg.writeEndArray();
        jg.writeEndObject();
        jg.close();
    }
}
