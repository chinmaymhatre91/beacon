/**
 * Copyright 2010-2013, Stanford University. This file is licensed under the
 * BSD license as described in the included LICENSE.txt.
 */
package net.beaconcontroller.web.view.section;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 *
 * @author David Erickson (daviderickson@cs.stanford.edu)
 */
public class StringSection extends JspSection {
    protected String content;

    public StringSection(String title, String content) {
        this.title = title;
        this.content = content;
        this.jspFileName = "string.jsp";
    }

    @Override
    public void render(HttpServletRequest request, HttpServletResponse response)
            throws Exception {
        request.setAttribute("title", title);
        request.setAttribute("content", content);
        super.render(request, response);
    }
}
