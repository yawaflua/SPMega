package qr

import (
	"errors"
	"fmt"
	"image"
	"image/color"
	"net/url"
	"strings"

	"github.com/makiuchi-d/gozxing"
	multiqrcode "github.com/makiuchi-d/gozxing/multi/qrcode"
	"github.com/makiuchi-d/gozxing/qrcode"
)

const maxPixels = 16_000_000

var (
	ErrNotFound         = errors.New("QR code not found")
	ErrMalformedCapture = errors.New("malformed screen capture")
)

type RGBA8Image struct {
	Width, Height int
	Stride        int
	Pixels        []byte
}

func DecodeRGBA8(width, height int, pixels []byte) (string, error) {
	return Decode(RGBA8Image{Width: width, Height: height, Stride: width * 4, Pixels: pixels})
}

func Decode(capture RGBA8Image) (string, error) {
	if capture.Width <= 0 || capture.Height <= 0 || capture.Width > maxPixels/capture.Height {
		return "", ErrMalformedCapture
	}
	if capture.Stride == 0 {
		capture.Stride = capture.Width * 4
	}
	if capture.Stride < capture.Width*4 || len(capture.Pixels) != capture.Stride*capture.Height {
		return "", ErrMalformedCapture
	}

	gray := image.NewGray(image.Rect(0, 0, capture.Width, capture.Height))
	for y := 0; y < capture.Height; y++ {
		row := capture.Pixels[y*capture.Stride:]
		for x := 0; x < capture.Width; x++ {
			offset := x * 4
			r, g, b := uint32(row[offset]), uint32(row[offset+1]), uint32(row[offset+2])
			gray.SetGray(x, y, color.Gray{Y: uint8((299*r + 587*g + 114*b + 500) / 1000)})
		}
	}

	source := gozxing.NewLuminanceSourceFromImage(gray)
	for _, candidate := range []gozxing.LuminanceSource{source, gozxing.NewInvertedLuminanceSource(source)} {
		if value, ok := decodeSource(candidate); ok {
			return value, nil
		}
	}
	return "", ErrNotFound
}

func decodeSource(source gozxing.LuminanceSource) (string, bool) {
	hints := map[gozxing.DecodeHintType]interface{}{
		gozxing.DecodeHintType_TRY_HARDER:       true,
		gozxing.DecodeHintType_POSSIBLE_FORMATS: []gozxing.BarcodeFormat{gozxing.BarcodeFormat_QR_CODE},
		gozxing.DecodeHintType_CHARACTER_SET:    "UTF-8",
	}
	binarizers := []gozxing.Binarizer{
		gozxing.NewHybridBinarizer(source),
		gozxing.NewGlobalHistgramBinarizer(source),
	}
	for _, binarizer := range binarizers {
		bitmap, err := gozxing.NewBinaryBitmap(binarizer)
		if err != nil {
			continue
		}
		result, err := qrcode.NewQRCodeReader().Decode(bitmap, hints)
		if err == nil && result != nil && result.GetText() != "" {
			return result.GetText(), true
		}
	}

	bitmap, err := gozxing.NewBinaryBitmap(gozxing.NewHybridBinarizer(source))
	if err == nil {
		results, decodeErr := multiqrcode.NewQRCodeMultiReader().DecodeMultiple(bitmap, hints)
		if decodeErr == nil && len(results) > 0 && results[0] != nil && results[0].GetText() != "" {
			return results[0].GetText(), true
		}
	}

	pureHints := make(map[gozxing.DecodeHintType]interface{}, len(hints)+1)
	for key, value := range hints {
		pureHints[key] = value
	}
	pureHints[gozxing.DecodeHintType_PURE_BARCODE] = true
	bitmap, err = gozxing.NewBinaryBitmap(gozxing.NewGlobalHistgramBinarizer(source))
	if err == nil {
		result, decodeErr := qrcode.NewQRCodeReader().Decode(bitmap, pureHints)
		if decodeErr == nil && result != nil && result.GetText() != "" {
			return result.GetText(), true
		}
	}
	return "", false
}

func ValidateURL(value string) error {
	target, err := url.Parse(value)
	if err != nil {
		return fmt.Errorf("parse QR URL: %w", err)
	}
	if target.Host == "" || (target.Scheme != "http" && target.Scheme != "https") || target.User != nil {
		return fmt.Errorf("unsupported QR URL")
	}
	host := strings.ToLower(target.Hostname())
	if host != "spworlds.ru" && !strings.HasSuffix(host, ".spworlds.ru") &&
		host != "spmega.yawaflua.tech" && !strings.HasSuffix(host, ".spmega.yawaflua.tech") &&
		host != "ywfl.dev" && !strings.HasSuffix(host, ".ywfl.dev") {
		return fmt.Errorf("untrusted QR URL host")
	}
	return nil
}
