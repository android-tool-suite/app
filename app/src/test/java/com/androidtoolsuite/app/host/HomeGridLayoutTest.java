package com.androidtoolsuite.app.host;

import org.json.JSONObject;
import org.json.JSONException;
import org.junit.Test;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import static org.junit.Assert.*;

public class HomeGridLayoutTest {
    @Test public void shortWidgetsFillSpaceBesideTallWidgetsAtOneUnitResolution() {
        var items=List.of(new HomeGridLayout.Item("short",2,1),new HomeGridLayout.Item("tall",2,2),new HomeGridLayout.Item("under",2,1));
        var cells=HomeGridLayout.place(items,Map.of());
        assertEquals(new HomeGridLayout.Cell(0,0),cells.get("short"));
        assertEquals(new HomeGridLayout.Cell(2,0),cells.get("tall"));
        assertEquals(new HomeGridLayout.Cell(0,1),cells.get("under"));
        assertNoOverlap(items,cells);
    }
    @Test public void arbitraryCoordinatesStayStableAndMovingReflowsOnlyCollisions() {
        var items=List.of(new HomeGridLayout.Item("moving",2,1),new HomeGridLayout.Item("other",2,2),new HomeGridLayout.Item("distant",1,1));
        var original=Map.of("moving",new HomeGridLayout.Cell(0,0),"other",new HomeGridLayout.Cell(2,0),"distant",new HomeGridLayout.Cell(3,8));
        var cells=HomeGridLayout.place(items,original,"moving",new HomeGridLayout.Cell(2,0));
        assertEquals(new HomeGridLayout.Cell(2,0),cells.get("moving"));
        assertEquals(new HomeGridLayout.Cell(3,8),cells.get("distant"));
        assertNotEquals(original.get("other"),cells.get("other"));assertNoOverlap(items,cells);
        assertEquals(cells,HomeGridLayout.place(items,cells));
    }
    @Test public void addingAndResizingDoesNotCompactTheExistingLayout() {
        var items=List.of(new HomeGridLayout.Item("new",1,1),new HomeGridLayout.Item("saved",2,2));
        var cells=HomeGridLayout.place(items,Map.of("saved",new HomeGridLayout.Cell(2,5)));
        assertEquals(new HomeGridLayout.Cell(2,5),cells.get("saved"));
        assertEquals(new HomeGridLayout.Cell(0,0),cells.get("new"));
        var resized=HomeGridLayout.place(List.of(new HomeGridLayout.Item("saved",4,2)),cells);
        assertEquals(new HomeGridLayout.Cell(0,0),resized.get("saved"));
    }
    @Test public void backupRoundTripAndMergeRetainCurrentCoordinatesAndSeparateFolderScopes() throws Exception {
        var current=new JSONObject().put("home",HomeGridLayout.encodeCells(Map.of("a",new HomeGridLayout.Cell(0,3))));
        var incoming=new JSONObject().put("home",HomeGridLayout.encodeCells(Map.of("a",new HomeGridLayout.Cell(2,8),"b",new HomeGridLayout.Cell(1,6))))
                .put("home:folder",HomeGridLayout.encodeCells(Map.of("a",new HomeGridLayout.Cell(3,2))));
        var merged=HomeGridLayout.merge(current,incoming);
        assertEquals(new HomeGridLayout.Cell(0,3),HomeGridLayout.decodeCells(merged.getJSONObject("home")).get("a"));
        assertEquals(new HomeGridLayout.Cell(1,6),HomeGridLayout.decodeCells(merged.getJSONObject("home")).get("b"));
        assertEquals(new HomeGridLayout.Cell(3,2),HomeGridLayout.decodeCells(merged.getJSONObject("home:folder")).get("a"));
        HomeGridLayout.validate(new JSONObject(merged.toString()));
    }
    @Test public void malformedCoordinatesAreRejectedWithoutIntegerCoercion() throws Exception {
        for(String raw:List.of("{\"a\":{\"column\":4,\"row\":0}}","{\"a\":{\"column\":0,\"row\":1.5}}","{\"a\":{\"column\":false,\"row\":0}}"))
            assertThrows(JSONException.class,()->HomeGridLayout.decodeCells(new JSONObject(raw)));
    }
    private static void assertNoOverlap(List<HomeGridLayout.Item> items,Map<String,HomeGridLayout.Cell> cells) {
        var occupied=new HashSet<Integer>();
        for(var item:items){var cell=cells.get(item.key);for(int y=cell.row;y<cell.row+item.height;y++)for(int x=cell.column;x<cell.column+item.width;x++)assertTrue(occupied.add(y*4+x));}
    }
}
