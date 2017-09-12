package com.example.vista.api;

import java.util.List;

/**
 * Created on 12/09/2017.
 * <p>
 * (c) 2017 Oracle Corporation
 */
public class DetectPlateResp {
    public boolean got_plate;
    public String plate;

    public List<Rect> rectangles;

}
