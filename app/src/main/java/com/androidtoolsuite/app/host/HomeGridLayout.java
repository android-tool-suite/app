package com.androidtoolsuite.app.host;

import org.json.JSONException;
import org.json.JSONObject;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One-unit occupancy for host widgets. Coordinates are independent of renderer and order. */
public final class HomeGridLayout {
    private HomeGridLayout() { }
    public static final int COLUMNS = 4;
    public static final int MAX_ROWS = 256;

    public static final class Cell {
        public final int column;
        public final int row;
        public Cell(int column, int row) {
            if (column < 0 || column >= COLUMNS || row < 0 || row >= MAX_ROWS) throw new IllegalArgumentException("Invalid grid cell");
            this.column=column;this.row=row;
        }
        @Override public boolean equals(Object other) { return other instanceof Cell && column==((Cell)other).column && row==((Cell)other).row; }
        @Override public int hashCode() { return row*COLUMNS+column; }
    }

    public static final class Item {
        public final String key;
        public final int width;
        public final int height;
        public Item(String key,int width,int height) {
            if (key==null || key.isBlank() || key.length()>256 || key.chars().anyMatch(Character::isISOControl)
                    || width<1 || width>COLUMNS || height<1 || height>4) throw new IllegalArgumentException("Invalid grid item");
            this.key=key;this.width=width;this.height=height;
        }
    }

    /** Valid saved positions stay in place; unplaced or resized items fill individual free cells. */
    public static Map<String,Cell> place(List<Item> items,Map<String,Cell> saved) { return place(items,saved,null,null); }
    public static Map<String,Cell> place(List<Item> items,Map<String,Cell> saved,String moving,Cell destination) {
        if(items.size()>512)throw new IllegalArgumentException("Too many grid items");
        Set<String> keys=new HashSet<>();for(Item item:items)if(!keys.add(item.key))throw new IllegalArgumentException("Duplicate grid item");
        Map<String,Cell> result=new LinkedHashMap<>();Set<Integer> occupied=new HashSet<>();
        if(moving!=null&&destination!=null)for(Item item:items)if(item.key.equals(moving)) {
            Cell clamped=new Cell(Math.min(destination.column,COLUMNS-item.width),Math.min(destination.row,MAX_ROWS-item.height));
            occupy(item,clamped,occupied);result.put(item.key,clamped);break;
        }
        for(Item item:items) {
            if(result.containsKey(item.key))continue;
            Cell cell=saved.get(item.key);
            if(cell!=null && fits(item,cell,occupied)){occupy(item,cell,occupied);result.put(item.key,cell);}
        }
        for(Item item:items)if(!result.containsKey(item.key)) {
            Cell cell=firstFree(item,occupied);occupy(item,cell,occupied);result.put(item.key,cell);
        }
        Map<String,Cell> ordered=new LinkedHashMap<>();for(Item item:items)ordered.put(item.key,result.get(item.key));
        return Collections.unmodifiableMap(ordered);
    }

    private static boolean fits(Item item,Cell cell,Set<Integer> occupied) {
        if(cell.column+item.width>COLUMNS || cell.row+item.height>MAX_ROWS)return false;
        for(int y=cell.row;y<cell.row+item.height;y++)for(int x=cell.column;x<cell.column+item.width;x++)if(occupied.contains(y*COLUMNS+x))return false;
        return true;
    }
    private static void occupy(Item item,Cell cell,Set<Integer> occupied) {
        for(int y=cell.row;y<cell.row+item.height;y++)for(int x=cell.column;x<cell.column+item.width;x++)occupied.add(y*COLUMNS+x);
    }
    private static Cell firstFree(Item item,Set<Integer> occupied) {
        for(int y=0;y<=MAX_ROWS-item.height;y++)for(int x=0;x<=COLUMNS-item.width;x++) {
            Cell cell=new Cell(x,y);if(fits(item,cell,occupied))return cell;
        }
        throw new IllegalArgumentException("Grid is full");
    }

    public static Map<String,Cell> decodeCells(JSONObject source) throws JSONException {
        Map<String,Cell> result=new LinkedHashMap<>();if(source==null)return result;
        if(source.length()>512)throw new JSONException("Too many grid positions");
        Iterator<String> keys=source.keys();while(keys.hasNext()) {
            String key=keys.next();JSONObject raw=source.getJSONObject(key);
            if(key.isBlank() || key.length()>256 || key.chars().anyMatch(Character::isISOControl)||raw.length()!=2)throw new JSONException("Invalid grid position");
            Object x=raw.get("column"),y=raw.get("row");
            if(!(x instanceof Number)||!(y instanceof Number)||((Number)x).doubleValue()!=((Number)x).intValue()||((Number)y).doubleValue()!=((Number)y).intValue())throw new JSONException("Invalid grid coordinates");
            try{result.put(key,new Cell(((Number)x).intValue(),((Number)y).intValue()));}
            catch(IllegalArgumentException error){throw new JSONException("Invalid grid coordinates");}
        }
        return result;
    }
    public static JSONObject encodeCells(Map<String,Cell> cells) {
        JSONObject result=new JSONObject();
        try{for(Map.Entry<String,Cell> entry:cells.entrySet())result.put(entry.getKey(),new JSONObject().put("column",entry.getValue().column).put("row",entry.getValue().row));}
        catch(JSONException error){throw new IllegalStateException(error);}
        return result;
    }
    public static void validate(JSONObject layouts) throws JSONException {
        if(layouts.length()>129)throw new JSONException("Too many grid scopes");
        Iterator<String> scopes=layouts.keys();int count=0;
        while(scopes.hasNext()) {
            String scope=scopes.next();
            if(!scope.equals("home")&&(!scope.startsWith("home:")||scope.length()>160||scope.chars().anyMatch(Character::isISOControl)))throw new JSONException("Invalid grid scope");
            count+=decodeCells(layouts.getJSONObject(scope)).size();
        }
        if(count>4096)throw new JSONException("Too many grid positions");
    }
    public static JSONObject merge(JSONObject current,JSONObject incoming) throws JSONException {
        validate(current);validate(incoming);JSONObject result=new JSONObject(current.toString());
        Iterator<String> scopes=incoming.keys();while(scopes.hasNext()) {
            String scope=scopes.next();Map<String,Cell> cells=decodeCells(result.optJSONObject(scope));
            for(Map.Entry<String,Cell> entry:decodeCells(incoming.getJSONObject(scope)).entrySet())cells.putIfAbsent(entry.getKey(),entry.getValue());
            result.put(scope,encodeCells(cells));
        }
        validate(result);return result;
    }
}
