package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"io/ioutil"
	"net/http"
	"os"
	"github.com/openalpr/openalpr/src/bindings/go/openalpr"
	"log"
)

type payloadIn struct {
	URL         string `json:"image_url"`
	CountryCode string `json:"countrycode"`
}

type payloadOut struct {
	GotPlate   bool     `json:"got_plate"`
	Rectangles []rectangle `json:"rectangles,omitempty"`
	Plate      string      `json:"plate,omitempty"`
}

type rectangle struct {
	StartX int `json:"startx"`
	StartY int `json:"starty"`
	EndX   int `json:"endx"`
	EndY   int `json:"endy"`
}

func main() {
	p := new(payloadIn)
	json.NewDecoder(os.Stdin).Decode(p)
	outfile := "working.jpg"

	alpr := openalpr.NewAlpr(p.CountryCode, "", "runtime_data")
	defer alpr.Unload()

	if !alpr.IsLoaded() {
		fmt.Println("OpenALPR failed to load!")
		return
	}
	alpr.SetTopN(10)

	log.Printf("Checking Plate URL ---> %s", p.URL)
	downloadFile(outfile, p.URL)

	imageBytes, err := ioutil.ReadFile(outfile)
	if err != nil {
		fmt.Println(err)
	}
	results, err := alpr.RecognizeByBlob(imageBytes)

	if len(results.Plates) > 0 {
		plate := results.Plates[0]
		log.Printf("\n\n FOUND PLATE ------>> %+v", plate)

		pout := &payloadOut{
			GotPlate:true,
			Rectangles: []rectangle{{StartX: plate.PlatePoints[0].X, StartY: plate.PlatePoints[0].Y, EndX: plate.PlatePoints[2].X, EndY: plate.PlatePoints[2].Y}},
			Plate:      plate.BestPlate,
		}

		log.Printf("\n\npout! --> %+v ", pout)

		b := new(bytes.Buffer)
		json.NewEncoder(b).Encode(pout)
		fmt.Print(b.String())

		//postURL := os.Getenv("FUNC_SERVER_URL") + "/draw"
		//res, _ := http.Post(postURL, "application/json", b)
		//fmt.Println(res.Body)
		//
		//alertPostURL := os.Getenv("FUNC_SERVER_URL") + "/alert"
		//resAlert, _ := http.Post(alertPostURL, "application/json", b2)
		//fmt.Println(resAlert.Body)
	} else {

		log.Println("No Plates Found!")
		b := new(bytes.Buffer)
		json.NewEncoder(b).Encode(&payloadOut{GotPlate:false})
		fmt.Print(b.String())

	}

}

func downloadFile(filepath string, url string) (err error) {
	// Create the file
	out, err := os.Create(filepath)
	if err != nil {
		return err
	}
	defer out.Close()

	// Get the data
	resp, err := http.Get(url)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	// Writer the body to file
	_, err = io.Copy(out, resp.Body)
	if err != nil {
		return err
	}

	return nil
}

var (
)

//func fnStart(bucket, id string) {
//	pubKey = os.Getenv("PUBNUB_PUBLISH_KEY")
//	subKey = os.Getenv("PUBNUB_SUBSCRIBE_KEY")
//
//	pn = messaging.NewPubnub(pubKey, subKey, "", "", false, "", nil)
//	go func() {
//		for {
//			select {
//			case msg := <-cbChannel:
//				fmt.Println(time.Now().Second(), ": ", string(msg))
//			case msg := <-errChan:
//				fmt.Println(string(msg))
//			default:
//			}
//		}
//	}()
//	pn.Publish(bucket, fmt.Sprintf(`{"type":"detect-plates","running":true, "id":"%s", "runner": "%s"}`, id, os.Getenv("HOSTNAME")), cbChannel, errChan)
//}

//func fnFinish(bucket, id string) {
//	pn.Publish(bucket, fmt.Sprintf(`{"type":"detect-plates","running":false, "id":"%s", "runner": "%s"}`, id, os.Getenv("HOSTNAME")), cbChannel, errChan)
//	time.Sleep(time.Second * 2)
//}

func init() {
	if os.Getenv("HOSTNAME") == "" {
		h, err := os.Hostname()
		if err == nil {
			os.Setenv("HOSTNAME", h)
		}
	}
}
