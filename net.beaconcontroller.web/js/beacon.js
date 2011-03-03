/**
 * Finds the index of the tab the specified element is in. If none is found
 * this function returns null.
 * @param element
 * @returns
 */
function findTabIndex(element) {
    var parents = $(element).parents('.ui-tabs-panel');
    var index = null;
    if (parents.size() > 0) {
        var tabId = parents.attr('id');
        var nav = $('.ui-tabs-nav');
        $(nav).find('a').each(function (idx, element) {
            if ($(element).attr('href') == ('#'+tabId)) {
                index = $(nav).children().index($(this).parent());
            }
        });
    }
    return index;
}

/**
 * Returns the element that is clicked to close the tab
 * @param index
 * @returns
 */
function getTabCloseElement(index) {
    var tab = $('.ui-tabs-nav').children()[index];
    var span = $(tab).find('span')[0];
    return span;
}

function DataTableWrapper(switchId, switchIdEsc) {
    var that = this;
    //$('#table-flows-new').addClass(switchId);
    $('#table-flows-'+switchIdEsc).addClass(switchId);
    //$('#table-flows-new').attr('id', 'table-flows-'+switchIdEsc);
    this.table = $('#table-flows-'+switchIdEsc).dataTable( {
        "bJQueryUI": true,
        "bPaginate": false,
        "bRetrieve": true,
        "bSort": true,
        "bStateSave": true,
        "iCookieDuration": 365*24*60*60, /* 1 year */
        //"oSearch": {"sSearch": "", "bRegex": false, "bSmart": true },
        "sAjaxSource": '/wm/core/switch/'+switchId+'/flows/dataTable',
        "sCookiePrefix": "beacon_flows_"+switchId,
        "sDom": '<"H"lfr>t<"F"ip>'
    });
    this.timerId = null;
    var filterBar = $('#table-flows-'+switchIdEsc+'_filter');

    // Have to add this by creating elements else the input field gets re-evaluated and loses its event handlers
    var button = document.createElement("button");
    $(button).attr("id", "button-refresh-"+switchIdEsc);
    $(button).attr("style", "margin-left: 5px;");
    $(button).html("Refresh");
    $(filterBar).append(button);
    button = document.createElement("button");
    $(button).attr("id", "button-autorefresh-"+switchIdEsc);
    $(button).attr("style", "margin-left: 5px;");
    $(button).html("Auto Refresh");
    $(filterBar).append(button);
    //filterBar.html('<button id="button-refresh-'+switchIdEsc+'" style="margin-left: 5px;">Refresh</button>' + filterBar.html());
//    filterBar.html(filterBar.html() + '<button id="button-autorefresh-'+switchIdEsc+'" style="margin-left: 5px;">Start Auto Refresh</button>');
    this.buttonRefresh = $('#button-refresh-'+switchIdEsc).button({ icons: {primary:'ui-icon-arrowrefresh-1-e'} });
    this.buttonAutoRefresh = $('#button-autorefresh-'+switchIdEsc).button({ icons: {primary:'ui-icon-arrowrefresh-1-e'} });
    this.buttonRefresh.click(function() { that.table.fnReloadAjax(); });
    this.buttonAutoRefresh.click(function () { that.startAutoRefresh(); });

    // Add a close handler to the tab to remove the timer
    var closer = getTabCloseElement(findTabIndex(this.table));
    $(closer).click(function() {
        if (that.timerId != null) {
            clearInterval(that.timerId);
        }
    });
}

DataTableWrapper.prototype.startAutoRefresh = function startAutoRefresh() {
    var that = this;
    this.timerId = setInterval(function () {
        that.table.fnReloadAjax();
    }, 5000);
    this.buttonAutoRefresh.button("option", "label", "Stop Auto Refresh");
    this.buttonAutoRefresh.button("option", "icons", {primary:'ui-icon-close'});
    this.buttonAutoRefresh.unbind('click');
    this.buttonAutoRefresh.click(function () { that.stopAutoRefresh(); });
}

DataTableWrapper.prototype.stopAutoRefresh = function startAutoRefresh() {
    var that = this;
    if (this.timerId != null) {
        clearInterval(this.timerId);
    }
    this.buttonAutoRefresh.button("option", "label", "Start Auto Refresh");
    this.buttonAutoRefresh.button("option", "icons", {primary:'ui-icon-arrowrefresh-1-e'});
    this.buttonAutoRefresh.unbind('click');
    this.buttonAutoRefresh.click(function () { that.startAutoRefresh(); });
    this.timerId = null;
}
