package es2plus

import (
	"bytes"
	"crypto/tls"
	"encoding/json"
	"fmt"
	"github.com/google/uuid"
	"log"
	"net/http"
	"strings"
)

///
///   External interface for ES2+ client
///
type Client interface {
	GetStatus(iccid string) (*ProfileStatus, error)
	RecoverProfile(iccid string, targetState string) (*ES2PlusRecoverProfileResponse, error)
	CancelOrder(iccid string, targetState string) (*ES2PlusCancelOrderResponse, error)
	DownloadOrder(iccid string) (*ES2PlusDownloadOrderResponse, error)
	ConfirmOrder(iccid string) (*ES2PlusConfirmOrderResponse, error)
	ActivateIccid(iccid string) (*ProfileStatus, error)
	RequesterId() string
}

///
///  Generic headers for invocations and responses
///
type es2PlusHeader struct {
	FunctionRequesterIdentifier string `json:"functionRequesterIdentifier"`
	FunctionCallIdentifier      string `json:"functionCallIdentifier"`
}

type ES2PlusGetProfileStatusRequest struct {
	Header    es2PlusHeader  `json:"header"`
	IccidList []es2PlusIccid `json:"iccidList"`
}

type es2PlusIccid struct {
	Iccid string `json:"iccid"`
}

type FunctionExecutionStatus struct {
	FunctionExecutionStatusType string                `json:"status"`
	StatusCodeData              ES2PlusStatusCodeData `json:"statusCodeData"`
}

type ES2PlusResponseHeader struct {
	FunctionExecutionStatus FunctionExecutionStatus `json:"functionExecutionStatus"`
}

//
//  Status code invocation.
//

type ES2PlusStatusCodeData struct {
	SubjectCode       string `json:"subjectCode"`
	ReasonCode        string `json:"reasonCode"`
	SubjectIdentifier string `json:"subjectIdentifier"`
	Message           string `json:"message"`
}

type ES2ProfileStatusResponse struct {
	Header              ES2PlusResponseHeader `json:"header"`
	ProfileStatusList   []ProfileStatus       `json:"profileStatusList"`
	CompletionTimestamp string                `json:"completionTimestamp"`
}

type ProfileStatus struct {
	StatusLastUpdateTimestamp string `json:"status_last_update_timestamp"`
	ACToken                   string `json:"acToken"`
	State                     string `json:"state"`
	Eid                       string `json:"eid"`
	Iccid                     string `json:"iccid"`
	LockFlag                  bool   `json:"lockFlag"`
}

//
//  Profile reset invocation
//

type ES2PlusRecoverProfileRequest struct {
	Header        es2PlusHeader `json:"header"`
	Iccid         string        `json:"iccid"`
	ProfileStatus string        `json:"profileStatus"`
}

type ES2PlusRecoverProfileResponse struct {
	Header ES2PlusResponseHeader `json:"header"`
}

//
//  Cancel order  invocation
//

type ES2PlusCancelOrderRequest struct {
	Header                      es2PlusHeader `json:"header"`
	Iccid                       string        `json:"iccid"`
	FinalProfileStatusIndicator string        `json:"finalProfileStatusIndicator"`
}

type ES2PlusCancelOrderResponse struct {
	Header ES2PlusResponseHeader `json:"header"`
}

//
//  Download order invocation
//

type ES2PlusDownloadOrderRequest struct {
	Header      es2PlusHeader `json:"header"`
	Iccid       string        `json:"iccid"`
	Eid         string        `json:"eid,omitempty"`
	Profiletype string        `json:"profiletype,omitempty"`
}

type ES2PlusDownloadOrderResponse struct {
	Header ES2PlusResponseHeader `json:"header"`
	Iccid  string                `json:"iccid"`
}

//
// ConfirmOrder invocation
//

type ES2PlusConfirmOrderRequest struct {
	Header           es2PlusHeader `json:"header"`
	Iccid            string        `json:"iccid"`
	Eid              string        `json:"eid,omitempty"`
	MatchingId       string        `json:"matchingId,omitempty"`
	ConfirmationCode string        `json:"confirmationCode,omitempty"`
	SmdpAddress      string        `json:"smdpAddress,omitempty"`
	ReleaseFlag      bool          `json:"releaseFlag"`
}

type ES2PlusConfirmOrderResponse struct {
	Header      ES2PlusResponseHeader `json:"header"`
	Iccid       string                `json:"iccid"`
	Eid         string                `json:"eid,omitempty"`
	MatchingId  string                `json:"matchingId,omitempty"`
	SmdpAddress string                `json:"smdpAddress,omitempty"`
}

//
//  Generating new ES2Plus clients
//

type ClientState struct {
	httpClient  *http.Client
	hostport    string
	requesterId string
	logPayload  bool
	logHeaders  bool
}

func NewClient(certFilePath string, keyFilePath string, hostport string, requesterId string) *ClientState {
	return &ClientState{
		httpClient:  newHttpClient(certFilePath, keyFilePath),
		hostport:    hostport,
		requesterId: requesterId,
		logPayload:  false,
		logHeaders:  false,
	}
}

func newHttpClient(certFilePath string, keyFilePath string) *http.Client {
	cert, err := tls.LoadX509KeyPair(
		certFilePath,
		keyFilePath)
	if err != nil {
		log.Fatalf("server: loadkeys: %s", err)
	}

	// TODO: The certificate used to sign the other end of the TLS connection
	//       is privately signed, and at this time we don't require the full
	//       certificate chain to  be available.
	config := tls.Config{Certificates: []tls.Certificate{cert}, InsecureSkipVerify: true}
	client := &http.Client{
		Transport: &http.Transport{
			TLSClientConfig: &config,
		},
	}
	return client
}

///
/// Generic protocol code
///

//
// Function used during debugging to print requests before they are
// sent over the wire.  Very useful, should not be deleted even though it
// is not used right now.
//
func formatRequest(r *http.Request) string {
	// Create return string
	var request []string
	// Add the request string
	url := fmt.Sprintf("%v %v %v", r.Method, r.URL, r.Proto)
	request = append(request, url)
	// Add the host
	request = append(request, fmt.Sprintf("Host: %v", r.Host))
	// Loop through headers
	for name, headers := range r.Header {
		name = strings.ToLower(name)
		for _, h := range headers {
			request = append(request, fmt.Sprintf("%v: %v", name, h))
		}
	}

	// If this is a POST, add post data
	if r.Method == "POST" {
		r.ParseForm()
		request = append(request, "\n")
		request = append(request, r.Form.Encode())
	}
	// Return the request as a string
	return strings.Join(request, "\n")
}

func newUuid() (string, error) {
	uuid, err := uuid.NewRandom()
	if err != nil {
		return "", err
	}
	return uuid.URN(), nil
}

func newEs2plusHeader(client Client) (*es2PlusHeader, error) {

	functionCallIdentifier, err := newUuid()
	if err != nil {
		return nil, err
	}

	return &es2PlusHeader{FunctionCallIdentifier: functionCallIdentifier, FunctionRequesterIdentifier: client.RequesterId()}, nil
}

func (client *ClientState) execute(
	es2plusCommand string,
	payload interface{}, result interface{}) error {

	// Serialize payload as json.
	jsonStrB := new(bytes.Buffer)
	err := json.NewEncoder(jsonStrB).Encode(payload)

	if err != nil {
		return err
	}

	if client.logPayload {
		log.Print("Payload ->", jsonStrB.String())
	}

	url := fmt.Sprintf("https://%s/gsma/rsp2/es2plus/%s", client.hostport, es2plusCommand)
	req, err := http.NewRequest("POST", url, jsonStrB)
	if err != nil {
		return err
	}
	req.Header.Set("X-Admin-Protocol", "gsma/rsp/v2.0.0")
	req.Header.Set("Content-Type", "application/json")

	if client.logHeaders {
		log.Printf("Request -> %s\n", formatRequest(req))
	}

	resp, err := client.httpClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	// TODO Should check response headers here!
	// (in particular X-admin-protocol) and fail if not OK.

	return json.NewDecoder(resp.Body).Decode(&result)
}

///
///  Externally visible API for Es2Plus protocol
///

func (client *ClientState) GetStatus(iccid string) (*ProfileStatus, error) {
	result := new(ES2ProfileStatusResponse)
	es2plusCommand := "getProfileStatus"
	header, err := newEs2plusHeader(client)
	if err != nil {
		return nil, err
	}
	payload := &ES2PlusGetProfileStatusRequest{
		Header:    *header,
		IccidList: []es2PlusIccid{es2PlusIccid{Iccid: iccid}},
	}
	if err = client.execute(es2plusCommand, payload, result); err != nil {
		return nil, err
	}

	if len(result.ProfileStatusList) == 0 {
		return nil, nil
	} else if len(result.ProfileStatusList) == 1 {
		return &result.ProfileStatusList[0], nil
	} else {
		return nil, fmt.Errorf("GetStatus returned more than one profile")
	}
}

func (client *ClientState) RecoverProfile(iccid string, targetState string) (*ES2PlusRecoverProfileResponse, error) {
	result := new(ES2PlusRecoverProfileResponse)
	es2plusCommand := "recoverProfile"
	header, err := newEs2plusHeader(client)
	if err != nil {
		return nil, err
	}
	payload := &ES2PlusRecoverProfileRequest{
		Header:        *header,
		Iccid:         iccid,
		ProfileStatus: targetState,
	}
	return result,  client.execute(es2plusCommand, payload, result)
}

func (client *ClientState) CancelOrder(iccid string, targetState string) (*ES2PlusCancelOrderResponse, error) {
	result := new(ES2PlusCancelOrderResponse)
	es2plusCommand := "cancelOrder"
	header, err := newEs2plusHeader(client)
	if err != nil {
		return nil, err
	}
	payload := &ES2PlusCancelOrderRequest{
		Header:                      *header,
		Iccid:                       iccid,
		FinalProfileStatusIndicator: targetState,
	}
	return result,  client.execute(es2plusCommand, payload, result)
}

func (client *ClientState) DownloadOrder(iccid string) (*ES2PlusDownloadOrderResponse, error) {
	result := new(ES2PlusDownloadOrderResponse)
	es2plusCommand := "downloadOrder"
	header, err := newEs2plusHeader(client)
	if err != nil {
		return nil, err
	}
	payload := &ES2PlusDownloadOrderRequest{
		Header:      *header,
		Iccid:       iccid,
		Eid:         "",
		Profiletype: "",
	}
	if err =  client.execute(es2plusCommand, payload, result); err != nil {
		return nil, err
	}

	executionStatus := result.Header.FunctionExecutionStatus.FunctionExecutionStatusType
	if executionStatus == "Executed-Success" {
		return result, nil
	} else {
		return result, fmt.Errorf("ExecutionStatus was: ''%s'", executionStatus)
	}
}

func (client *ClientState) ConfirmOrder(iccid string) (*ES2PlusConfirmOrderResponse, error) {
	result := new(ES2PlusConfirmOrderResponse)
	es2plusCommand := "confirmOrder"
	header, err := newEs2plusHeader(client)
	if err != nil {
		return nil, err
	}
	payload := &ES2PlusConfirmOrderRequest{
		Header:           *header,
		Iccid:            iccid,
		Eid:              "",
		ConfirmationCode: "",
		MatchingId:       "",
		SmdpAddress:      "",
		ReleaseFlag:      true,
	}

	if err =  client.execute(es2plusCommand, payload, result); err != nil {
		return nil, err
	}

	executionStatus := result.Header.FunctionExecutionStatus.FunctionExecutionStatusType
	if executionStatus != "Executed-Success" {
		return result, fmt.Errorf("ExecutionStatus was: ''%s'", executionStatus)
	} else {
		return result, nil
	}
}

func (client *ClientState) ActivateIccid(iccid string) (*ProfileStatus, error) {

	result, err := client.GetStatus(iccid)
	if err != nil {
		return nil, err
	}

	if result.ACToken == "" {

		if result.State == "AVAILABLE" {
			if _, err := client.DownloadOrder(iccid); err != nil {
				return nil, err
			}
			if result, err = client.GetStatus(iccid); err != nil {
				return nil, err
			}
		}

		if result.State == "ALLOCATED" {
			if _, err = client.ConfirmOrder(iccid); err != nil {
				return nil, err
			}
		}
	}
	result, err = client.GetStatus(iccid)
	return result, err
}

// TODO: This shouldn't have to be public, but how can it be avoided?
func (clientState *ClientState) RequesterId() string {
	return clientState.requesterId
}
