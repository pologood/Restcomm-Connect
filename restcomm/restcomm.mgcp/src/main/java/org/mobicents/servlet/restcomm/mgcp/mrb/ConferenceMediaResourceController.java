/*
 * TeleStax, Open Source Cloud Communications
 * Copyright 2011-2013, Telestax Inc and individual contributors
 * by the @authors tag.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.mobicents.servlet.restcomm.mgcp.mrb;

import java.net.URI;
import java.net.URISyntaxException;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.configuration.Configuration;
import org.joda.time.DateTime;
import org.mobicents.servlet.restcomm.dao.ConferenceDetailRecordsDao;
import org.mobicents.servlet.restcomm.dao.DaoManager;
import org.mobicents.servlet.restcomm.dao.MediaResourceBrokerDao;
import org.mobicents.servlet.restcomm.entities.ConferenceDetailRecord;
import org.mobicents.servlet.restcomm.entities.MediaResourceBrokerEntity;
import org.mobicents.servlet.restcomm.entities.MediaResourceBrokerEntityFilter;
import org.mobicents.servlet.restcomm.entities.Sid;
import org.mobicents.servlet.restcomm.fsm.Action;
import org.mobicents.servlet.restcomm.fsm.FiniteStateMachine;
import org.mobicents.servlet.restcomm.fsm.State;
import org.mobicents.servlet.restcomm.fsm.Transition;
import org.mobicents.servlet.restcomm.mgcp.ConnectionStateChanged;
import org.mobicents.servlet.restcomm.mgcp.CreateConferenceEndpoint;
import org.mobicents.servlet.restcomm.mgcp.CreateConnection;
import org.mobicents.servlet.restcomm.mgcp.CreateIvrEndpoint;
import org.mobicents.servlet.restcomm.mgcp.DestroyEndpoint;
import org.mobicents.servlet.restcomm.mgcp.EndpointCredentials;
import org.mobicents.servlet.restcomm.mgcp.InitializeConnection;
import org.mobicents.servlet.restcomm.mgcp.InviteEndpoint;
import org.mobicents.servlet.restcomm.mgcp.MediaGatewayResponse;
import org.mobicents.servlet.restcomm.mgcp.MediaSession;
import org.mobicents.servlet.restcomm.mgcp.OpenConnection;
import org.mobicents.servlet.restcomm.mgcp.UpdateConnection;
import org.mobicents.servlet.restcomm.mgcp.mrb.messages.ConferenceMediaResourceControllerStateChanged;
import org.mobicents.servlet.restcomm.mgcp.mrb.messages.StartConferenceMediaResourceController;
import org.mobicents.servlet.restcomm.mgcp.mrb.messages.StopConferenceMediaResourceController;
import org.mobicents.servlet.restcomm.mgcp.mrb.messages.StopConferenceMediaResourceControllerResponse;
import org.mobicents.servlet.restcomm.mscontrol.messages.MediaGroupResponse;
import org.mobicents.servlet.restcomm.mscontrol.messages.MediaGroupStateChanged;
import org.mobicents.servlet.restcomm.mscontrol.messages.Play;
import org.mobicents.servlet.restcomm.mscontrol.messages.Record;
import org.mobicents.servlet.restcomm.mscontrol.messages.StartMediaGroup;
import org.mobicents.servlet.restcomm.mscontrol.messages.StartRecording;
import org.mobicents.servlet.restcomm.mscontrol.messages.Stop;
import org.mobicents.servlet.restcomm.mscontrol.messages.StopMediaGroup;
import org.mobicents.servlet.restcomm.mscontrol.messages.StopRecording;
import org.mobicents.servlet.restcomm.patterns.Observe;
import org.mobicents.servlet.restcomm.patterns.Observing;
import org.mobicents.servlet.restcomm.patterns.StopObserving;
import org.mobicents.servlet.restcomm.util.UriUtils;

import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.actor.UntypedActorFactory;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import jain.protocol.ip.mgcp.message.parms.ConnectionDescriptor;
import jain.protocol.ip.mgcp.message.parms.ConnectionMode;
import jain.protocol.ip.mgcp.message.parms.EndpointIdentifier;

/**
 * @author maria.farooq@telestax.com (Maria Farooq)
 */
public class ConferenceMediaResourceController extends UntypedActor{

    private final LoggingAdapter logger = Logging.getLogger(getContext().system(), this);

    // Finite State Machine
    private final FiniteStateMachine fsm;
    private final State uninitialized;
    private final State initialized;
    private final State acquiringConferenceInfo;
    private final State creatingMediaGroup;
    private final State acquiringIVREndpointID;
    //for slave
    private final State acquiringRemoteConnectionWithLocalMS;
    private final State initializingRemoteConnectionWithLocalMS;
    private final State openingRemoteConnectionWithLocalMS;
    private final State updatingRemoteConnectionWithLocalMS;
    private final State acquiringMediaSessionWithMasterMS;
    private final State acquiringMasterConferenceEndpoint;
    private final State acquiringRemoteConnectionWithMasterMS;
    private final State initializingRemoteConnectionWithMasterMS;
    private final State openingRemoteConnectionWithMasterMS;
    //for Master
    private final State acquiringConferenceEndpointID;
    private final State active;
    private final State stopping;
    private final State inactive;
    private final State failed;

    private final Map<String, ActorRef> allMediaGateways;
    private final ActorRef localMediaGateway;
    private ActorRef masterMediaGateway;
    private ActorRef mediaGroup;

    private String localMsId;
    private String masterMsId;
    private boolean isThisMaster = false;
    private String localMediaServerSdp;
    private String masterMediaServerSdp;
    public EndpointIdentifier masterConfernceEndpointId;
    public EndpointIdentifier masterIVREndpointId;
    public String masterConfernceEndpointIdName;
    public String masterIVREndpointIdName;
    private MediaSession localMediaSession;
    private MediaSession masterMediaSession;
    private ActorRef localConfernceEndpoint;
    private ActorRef masterConfernceEndpoint;
    private ActorRef masterIVREndpoint;
    private ActorRef connectionWithLocalMS;
    private ActorRef connectionWithMasterMS;

    private final DaoManager storage;
    private final Configuration configuration;
    private MediaResourceBrokerEntity entity;
    private ConferenceDetailRecord cdr;
    private Sid conferenceSid;

    // Runtime media operations
    private Boolean playing;
    private Boolean fail;
    private Boolean recording;
    private DateTime recordStarted;

    // Observer pattern
    private final List<ActorRef> observers;

    private boolean areAnySlavesConnectedToThisConferenceEndpoint;
    private int noOfConnectedSlaves = 0;

    public ConferenceMediaResourceController(final String localMsId, final Map<String, ActorRef> gateways, final Configuration configuration, final DaoManager storage){
        super();
        final ActorRef source = self();
        // Initialize the states for the FSM.
        this.uninitialized = new State("uninitialized", null, null);
        this.initialized = new State("initialized", new Initialized(source), null);
        this.creatingMediaGroup = new State("creating media group", new CreatingMediaGroup(source), null);
        this.acquiringConferenceInfo = new State("getting Conference Info From DB", new AcquiringConferenceInfo(source), null);
        this.acquiringIVREndpointID=new State("acquiring IVR endpoint ID", new AcquiringIVREndpointID(source), new SavingIVREndpointID(source));
        this.acquiringConferenceEndpointID=new State("acquiring ConferenceEndpoint ID", new AcquiringConferenceEndpointID(source), new SavingConferenceEndpointID(source));
        this.acquiringRemoteConnectionWithLocalMS = new State("acquiring connection with local media server", new AcquiringRemoteConnectionWithLocalMS(source), null);
        this.initializingRemoteConnectionWithLocalMS = new State("initializing connection with local media server", new InitializingRemoteConnectionWithLocalMS(source), null);
        this.openingRemoteConnectionWithLocalMS = new State("opening connection", new OpeningRemoteConnection(source), null);
        this.updatingRemoteConnectionWithLocalMS = new State("updating RemoteConnection With Local MS", new UpdatingRemoteConnectionWithLocalMS(source), null);
        this.acquiringMediaSessionWithMasterMS = new State("acquiring MediaSession With Master MS", new AcquiringMediaSessionWithMasterMS(source), null);
        this.acquiringMasterConferenceEndpoint = new State("acquiring Master ConferenceEndpoint", new AcquiringMasterIVREndpoint(source), null);
        this.acquiringRemoteConnectionWithMasterMS = new State("acquiring RemoteConnection With Master MS", new AcquiringRemoteConnectionWithMasterMS(source), null);
        this.initializingRemoteConnectionWithMasterMS = new State("initializing RemoteConnection With Master MS", new InitializingRemoteConnectionWithMasterMS(source), null);
        this.openingRemoteConnectionWithMasterMS = new State("opening RemoteConnection With Master MS", new OpeningRemoteConnectionWithMasterMS(source), null);
        this.active = new State("active", new Active(source));
        this.stopping = new State("stopping", new Stopping(source));
        this.inactive = new State("inactive", new Inactive(source));
        this.failed = new State("failed", new Failed(source));

        // Transitions for the FSM.
        final Set<Transition> transitions = new HashSet<Transition>();
        //states for master
        transitions.add(new Transition(uninitialized, acquiringConferenceInfo));
        transitions.add(new Transition(acquiringConferenceInfo, creatingMediaGroup));
        transitions.add(new Transition(creatingMediaGroup, acquiringIVREndpointID));
        transitions.add(new Transition(acquiringIVREndpointID, acquiringConferenceEndpointID));
        transitions.add(new Transition(acquiringConferenceEndpointID, active));
        //states for slave
        transitions.add(new Transition(acquiringConferenceInfo, acquiringMediaSessionWithMasterMS));
        transitions.add(new Transition(acquiringMediaSessionWithMasterMS, acquiringRemoteConnectionWithLocalMS));
        transitions.add(new Transition(acquiringRemoteConnectionWithLocalMS, initializingRemoteConnectionWithLocalMS));
        transitions.add(new Transition(initializingRemoteConnectionWithLocalMS, openingRemoteConnectionWithLocalMS));
        transitions.add(new Transition(openingRemoteConnectionWithLocalMS, failed));
        transitions.add(new Transition(openingRemoteConnectionWithLocalMS, acquiringMasterConferenceEndpoint));
        transitions.add(new Transition(acquiringMasterConferenceEndpoint, acquiringRemoteConnectionWithMasterMS));
        transitions.add(new Transition(acquiringRemoteConnectionWithMasterMS, initializingRemoteConnectionWithMasterMS));
        transitions.add(new Transition(initializingRemoteConnectionWithMasterMS, openingRemoteConnectionWithMasterMS));
        transitions.add(new Transition(openingRemoteConnectionWithMasterMS, updatingRemoteConnectionWithLocalMS));
        transitions.add(new Transition(updatingRemoteConnectionWithLocalMS, creatingMediaGroup));
        transitions.add(new Transition(creatingMediaGroup, active));
        transitions.add(new Transition(active, stopping));
        transitions.add(new Transition(stopping, inactive));

        // Initialize the FSM.
        this.fsm = new FiniteStateMachine(uninitialized, transitions);

        this.storage = storage;
        this.configuration = configuration;
        this.allMediaGateways = gateways;
        logger.info("localMsId: "+localMsId);
        this.localMsId = localMsId;
        this.localMediaGateway = allMediaGateways.get(this.localMsId);
        masterIVREndpointIdName = null;

        // Runtime media operations
        this.playing = Boolean.FALSE;
        this.recording = Boolean.FALSE;
        this.fail = Boolean.FALSE;

        // Observers
        this.observers = new ArrayList<ActorRef>(1);
    }

    private boolean is(State state) {
        return this.fsm.state().equals(state);
    }

    private void broadcast(Object message) {
        if (!this.observers.isEmpty()) {
            final ActorRef self = self();
            synchronized (this.observers) {
                for (ActorRef observer : observers) {
                    observer.tell(message, self);
                }
            }
        }
    }

    @Override
    public void onReceive(Object message) throws Exception {
        final Class<?> klass = message.getClass();
        final ActorRef sender = sender();
        ActorRef self = self();
        final State state = fsm.state();

        if (logger.isInfoEnabled()) {
            logger.info(" ********** ConferenceMediaResourceController " + self().path() + " Processing Message: " + klass.getName());
            logger.info(" ********** ConferenceMediaResourceController " + self().path() + " Current State: \"" + state.toString());
        }

        if (Observe.class.equals(klass)) {
            onObserve((Observe) message, self, sender);
        } else if (StopObserving.class.equals(klass)) {
            onStopObserving((StopObserving) message, self, sender);
        } else if (StartConferenceMediaResourceController.class.equals(klass)){
            onStartConferenceMediaResourceController((StartConferenceMediaResourceController) message, self, sender);
        } /*else if (JoinConferences.class.equals(klass)){
            onJoinConferences((JoinConferences) message, self, sender);
        }*/ else if (MediaGatewayResponse.class.equals(klass)) {
            logger.info("going to call onMediaGatewayResponse");
            onMediaGatewayResponse((MediaGatewayResponse<?>) message, self, sender);
        } else if (ConnectionStateChanged.class.equals(klass)) {
            onConnectionStateChanged((ConnectionStateChanged) message, self, sender);
        } else if (EndpointCredentials.class.equals(klass)) {
            onEndpointCredentials((EndpointCredentials) message, self, sender);
        } else if (MediaGroupStateChanged.class.equals(klass)) {
            onMediaGroupStateChanged((MediaGroupStateChanged) message, self, sender);
        } else if (StopMediaGroup.class.equals(klass)) {
            onStopMediaGroup((StopMediaGroup) message, self, sender);
        } else if (Play.class.equals(klass)) {
            onPlay((Play) message, self, sender);
        } else if(MediaGroupResponse.class.equals(klass)) {
            onMediaGroupResponse((MediaGroupResponse<String>) message, self, sender);
        } else if (StartRecording.class.equals(klass)) {
            onStartRecording((StartRecording) message, self, sender);
        } else if (StopRecording.class.equals(klass)) {
            onStopRecording((StopRecording) message, self, sender);
        } else if (StopConferenceMediaResourceController.class.equals(klass)) {
            onStopConferenceMediaResourceController((StopConferenceMediaResourceController) message, self, sender);
        }
    }

    private void onObserve(Observe message, ActorRef self, ActorRef sender) {
        final ActorRef observer = message.observer();
        if (observer != null) {
            synchronized (this.observers) {
                this.observers.add(observer);
                observer.tell(new Observing(self), self);
            }
        }
    }

    private void onStopObserving(StopObserving message, ActorRef self, ActorRef sender) {
        final ActorRef observer = message.observer();
        if (observer != null) {
            this.observers.remove(observer);
        }
    }

    private void onStartConferenceMediaResourceController(StartConferenceMediaResourceController message, ActorRef self, ActorRef sender) throws Exception{
        if (is(uninitialized)) {
            logger.info("onStartConferenceMediaResourceController: conferenceSid: "+message.conferenceSid()+" cnfEndpoint: "+message.cnfEndpoint());
            this.localConfernceEndpoint = message.cnfEndpoint();
            this.conferenceSid = message.conferenceSid();
            fsm.transition(message, acquiringConferenceInfo);
        }
    }

    private void onEndpointCredentials(EndpointCredentials message, ActorRef self, ActorRef sender) throws Exception{
        logger.info("onEndpointCredentials state = "+fsm.state());
        if(is(acquiringIVREndpointID)){
            fsm.transition(message, acquiringConferenceEndpointID);
        }else{
            fsm.transition(message, active);
        }
    }

    /*private void onJoinConferences(JoinConferences message, ActorRef self, ActorRef sender) throws Exception{
        if(logger.isDebugEnabled())
            logger.debug("onJoinConferences: current state is: "+fsm.state());
        if (is(initialized)) {
            if(isThisMaster){
                this.fsm.transition(message, acquiringConferenceEndpointID);
            }else{
                this.fsm.transition(message, acquiringRemoteConnectionWithLocalMS);
            }
        }
    }*/

    private void onMediaGatewayResponse(MediaGatewayResponse<?> message, ActorRef self, ActorRef sender) throws Exception {
        logger.info("inside onMediaGatewayResponse: state = "+fsm.state());
        if (is(acquiringConferenceInfo)){
            logger.info("^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^ onMediaGatewayResponse - acquiringMediaSession ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^");
            this.localMediaSession = (MediaSession) message.get();
            if(isThisMaster){
                this.fsm.transition(message, creatingMediaGroup);
            }else{
                this.fsm.transition(message, acquiringMediaSessionWithMasterMS);
            }
        } else if (is(acquiringRemoteConnectionWithLocalMS)){
            logger.info("^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^ onMediaGatewayResponse - acquiringRemoteConnection ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^");
            fsm.transition(message, initializingRemoteConnectionWithLocalMS);
        } else if (is(acquiringMediaSessionWithMasterMS)) {
            this.masterMediaSession = (MediaSession) message.get();
            logger.info("^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^ onMediaGatewayResponse - acquiringMediaSessionWithMasterMS"+" masterMediaSession is "+masterMediaSession+" ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^");
            fsm.transition(message, acquiringRemoteConnectionWithLocalMS);
        } else if (is(acquiringMasterConferenceEndpoint)){
            logger.info("^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^ onMediaGatewayResponse - acquiringMasterConferenceEndpoint"+" masterConfernceEndpoint is "+masterConfernceEndpoint+" ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^");
            masterConfernceEndpoint = (ActorRef) message.get();
            masterConfernceEndpoint.tell(new Observe(self), self);
            fsm.transition(message, acquiringRemoteConnectionWithMasterMS);
        } else if (is(acquiringRemoteConnectionWithMasterMS)) {
            fsm.transition(message, initializingRemoteConnectionWithMasterMS);
        }
    }

    private void onConnectionStateChanged(ConnectionStateChanged message, ActorRef self, ActorRef sender) throws Exception {
        logger.info("^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^ onConnectionStateChanged - received connection STATE is: "+message.state()+" current fsm STATE is: "+fsm.state()+" ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^");
        switch (message.state()) {
            case CLOSED:
                if (is(initializingRemoteConnectionWithLocalMS)) {
                    fsm.transition(message, openingRemoteConnectionWithLocalMS);
                } else if (is(initializingRemoteConnectionWithMasterMS)) {
                    fsm.transition(message, openingRemoteConnectionWithMasterMS);
                } else if (is(openingRemoteConnectionWithLocalMS) || is(openingRemoteConnectionWithMasterMS)) {
                    fsm.transition(message, failed);
                }
                break;

            case HALF_OPEN:
                if (is(openingRemoteConnectionWithLocalMS)){
                    ConnectionStateChanged connState = (ConnectionStateChanged) message;
                    localMediaServerSdp = connState.descriptor().toString();
                    logger.info("^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^ localMediaServerSdp: "+localMediaServerSdp);
                    fsm.transition(message, acquiringMasterConferenceEndpoint);
                    break;
                }
                break;
            case OPEN:
                if (is(openingRemoteConnectionWithMasterMS)){
                    ConnectionStateChanged connState = (ConnectionStateChanged) message;
                    masterMediaServerSdp = connState.descriptor().toString();
                    logger.info("^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^ masterMediaServerSdp: "+masterMediaServerSdp);
                    fsm.transition(message, updatingRemoteConnectionWithLocalMS);
                } else if (is(updatingRemoteConnectionWithLocalMS)){
                    fsm.transition(message, creatingMediaGroup);
                }
                break;

            default:
                break;
        }
    }

    private void onStopConferenceMediaResourceController(StopConferenceMediaResourceController message, ActorRef self,
            ActorRef sender) throws Exception {
        areAnySlavesConnectedToThisConferenceEndpoint = areAnySlavesConnectedToThisConferenceEndpoint();
        logger.info("areAnySlavesConnectedToThisConferenceEndpoint = "+areAnySlavesConnectedToThisConferenceEndpoint);
        if(areAnySlavesConnectedToThisConferenceEndpoint){
            playBeepOnExit(self);
        }
        if(isThisMaster){
            logger.info("onStopConferenceMediaResourceController");
            sender.tell(new StopConferenceMediaResourceControllerResponse(!areAnySlavesConnectedToThisConferenceEndpoint), sender);
        }else{
            sender.tell(new StopConferenceMediaResourceControllerResponse(true), sender);
        }
        fsm.transition(message, stopping);
    }

    private void onMediaGroupStateChanged(MediaGroupStateChanged message, ActorRef self, ActorRef sender) throws Exception {
        logger.info("^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^ onMediaGroupStateChanged - received STATE is: "+message.state()+" current fsm STATE is: "+fsm.state()+" ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^");
        switch (message.state()) {
            case ACTIVE:
                if (is(creatingMediaGroup)) {
                    this.masterIVREndpoint = message.ivr();
                    if(isThisMaster){
                        fsm.transition(message, acquiringIVREndpointID);
                    }else{
                        fsm.transition(message, active);
                    }
                }
                break;

            case INACTIVE:
                if (is(creatingMediaGroup)) {
                    this.fail = Boolean.TRUE;
                    fsm.transition(message, failed);
                } else if (is(stopping)) {
                    // Stop media group actor
                    this.mediaGroup.tell(new StopObserving(self), self);
                    context().stop(mediaGroup);
                    this.mediaGroup = null;

                    // Move to next state
                    if (this.mediaGroup == null && this.localConfernceEndpoint == null) {
                        this.fsm.transition(message, fail ? failed : inactive);
                    }
                }
                break;

            default:
                break;
        }
    }

    private void onPlay(Play message, ActorRef self, ActorRef sender) {
        if (is(active) && !playing) {
            this.playing = Boolean.TRUE;
            this.mediaGroup.tell(message, self);
        }
    }

    private void onStartRecording(StartRecording message, ActorRef self, ActorRef sender) throws Exception {
        if (is(active) && !recording) {
            String finishOnKey = "1234567890*#";
            int maxLength = 3600;
            int timeout = 5;

            this.recording = Boolean.TRUE;
            this.recordStarted = DateTime.now();

            // Tell media group to start recording
            Record record = new Record(message.getRecordingUri(), timeout, maxLength, finishOnKey);
            this.mediaGroup.tell(record, null);
        }
    }

    private void onStopRecording(StopRecording message, ActorRef self, ActorRef sender) throws Exception {
        if (is(active) && recording) {
            this.recording = Boolean.FALSE;
            mediaGroup.tell(new Stop(), null);
        }
    }

    private void onMediaGroupResponse(MediaGroupResponse<String> message, ActorRef self, ActorRef sender) throws Exception {
        if (is(active) && this.playing) {
            this.playing = Boolean.FALSE;
        }
    }

    private void onStopMediaGroup(StopMediaGroup message, ActorRef self, ActorRef sender) {
        //if (is(active)) {
            // Stop the primary media group
            this.mediaGroup.tell(new Stop(), self);
            this.playing = Boolean.FALSE;
        //}
    }

    /*
     * ACTIONS
     *
     */
    protected abstract class AbstractAction implements Action {

        protected final ActorRef source;

        public AbstractAction(final ActorRef source) {
            super();
            this.source = source;
        }
    }

    private final class AcquiringConferenceInfo extends AbstractAction {

        public AcquiringConferenceInfo(final ActorRef source) {
            super(source);
        }

        @Override
        public void execute(final Object msg) throws Exception {
            logger.info("current state is: "+fsm.state());
            //check master MS info from DB
            final ConferenceDetailRecordsDao conferenceDetailRecordsDao = storage.getConferenceDetailRecordsDao();
            cdr = conferenceDetailRecordsDao.getConferenceDetailRecord(conferenceSid);
            if(cdr == null){
                logger.error("there is no information available in DB to proceed with this CMRC");
                fsm.transition(msg, failed);
            }else{
                //msId in conference record is master msId
                masterMsId = cdr.getMasterMsId();
                if(localMsId.equalsIgnoreCase(masterMsId)){
                    logger.info("first participant Joined on master MS and sent message to CMRC");
                    isThisMaster = true;
                }else{
                    masterMediaGateway = allMediaGateways.get(masterMsId);
                    masterConfernceEndpointIdName = cdr.getMasterConferenceEndpointId();
                    masterIVREndpointIdName = cdr.getMasterIVREndpointId();
                    logger.info("masterMediaGateway acquired: "+masterMediaGateway);
                    logger.info("new slave sent StartBridgeConnector message to CMRC masterIVREndpointId: "+masterIVREndpointIdName);
                }
                logger.info("^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^ AcquiringMediaSession ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^");
                localMediaGateway.tell(new org.mobicents.servlet.restcomm.mgcp.CreateMediaSession(), source);
            }
        }

    }

    private final class CreatingMediaGroup extends AbstractAction {

        public CreatingMediaGroup(ActorRef source) {
            super(source);
        }

        private ActorRef createMediaGroup(final Object message) {
            return getContext().actorOf(new Props(new UntypedActorFactory() {
                private static final long serialVersionUID = 1L;

                @Override
                public UntypedActor create() throws Exception {
                    if(isThisMaster){
                        return new MgcpMediaGroup(localMediaGateway, localMediaSession, localConfernceEndpoint, masterIVREndpointIdName);
                    }else{
                        return new MgcpMediaGroup(localMediaGateway, localMediaSession, localConfernceEndpoint, masterIVREndpointIdName);
                    }
                }
            }));
        }

        @Override
        public void execute(Object message) throws Exception {
            mediaGroup = createMediaGroup(message);
            mediaGroup.tell(new Observe(super.source), super.source);
            mediaGroup.tell(new StartMediaGroup(), super.source);
        }
    }

    private final class Initialized extends AbstractAction {

        public Initialized(ActorRef source) {
            super(source);
        }

        @Override
        public void execute(Object message) throws Exception {
            logger.info("^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^ Initialized ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^");
            broadcast(new ConferenceMediaResourceControllerStateChanged(ConferenceMediaResourceControllerStateChanged.MediaServerControllerState.INITIALIZED));
        }

    }

    private final class AcquiringIVREndpointID extends AbstractAction {
        public AcquiringIVREndpointID(final ActorRef source) {
            super(source);
        }

        @Override
        public void execute(final Object message) throws Exception {
            if (masterIVREndpoint != null) {
                final InviteEndpoint invite = new InviteEndpoint();
                masterIVREndpoint.tell(invite, source);
            }
        }
    }

    private final class SavingIVREndpointID extends AbstractAction {
        public SavingIVREndpointID(final ActorRef source) {
            super(source);
        }

        @Override
        public void execute(final Object message) throws Exception {
            final EndpointCredentials response = (EndpointCredentials) message;
            masterIVREndpointId = response.endpointId();
            masterIVREndpointIdName = masterIVREndpointId.getLocalEndpointName();
            logger.info("^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^ masterIVREndpointId:"+masterIVREndpointIdName+" ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^");
            updateMasterIVREndpointId();
        }
    }

    private final class AcquiringConferenceEndpointID extends AbstractAction {
        public AcquiringConferenceEndpointID(final ActorRef source) {
            super(source);
        }

        @Override
        public void execute(final Object message) throws Exception {
            if (localConfernceEndpoint != null) {
                final InviteEndpoint invite = new InviteEndpoint();
                localConfernceEndpoint.tell(invite, source);
            }
        }
    }

    private final class SavingConferenceEndpointID extends AbstractAction {
        public SavingConferenceEndpointID(final ActorRef source) {
            super(source);
        }

        @Override
        public void execute(final Object message) throws Exception {
            final EndpointCredentials response = (EndpointCredentials) message;
            masterConfernceEndpointId = response.endpointId();
            logger.info("^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^ localConfernceEndpointId:"+masterConfernceEndpointId+" ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^");
            updateMasterConferenceEndpointId();
        }
    }

    private final class AcquiringRemoteConnectionWithLocalMS extends AbstractAction {

        public AcquiringRemoteConnectionWithLocalMS(final ActorRef source) {
            super(source);
        }

        @Override
        public void execute(final Object message) throws Exception {
            localMediaGateway.tell(new CreateConnection(localMediaSession), source);
        }
    }

    private final class InitializingRemoteConnectionWithLocalMS extends AbstractAction {

        public InitializingRemoteConnectionWithLocalMS(ActorRef source) {
            super(source);
        }

        @SuppressWarnings("unchecked")
        @Override
        public void execute(final Object message) throws Exception {
            final MediaGatewayResponse<ActorRef> response = (MediaGatewayResponse<ActorRef>) message;
            connectionWithLocalMS = response.get();
            connectionWithLocalMS.tell(new Observe(source), source);
            logger.info("^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^ InitializingRemoteConnection -  - received connectionWithLocalMS:"+connectionWithLocalMS+" | localConfernceEndpoint: "+localConfernceEndpoint+" ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^");
            connectionWithLocalMS.tell(new InitializeConnection(localConfernceEndpoint), source);
        }
    }

    private final class OpeningRemoteConnection extends AbstractAction {
        public OpeningRemoteConnection(final ActorRef source) {
            super(source);
        }

        @Override
        public void execute(final Object message) throws Exception {
            logger.info("OpeningRemoteConnection...");
            OpenConnection open = new OpenConnection(ConnectionMode.SendRecv, false);
            connectionWithLocalMS.tell(open, source);
        }
    }

    private final class AcquiringMediaSessionWithMasterMS extends AbstractAction {

        public AcquiringMediaSessionWithMasterMS(final ActorRef source) {
            super(source);
        }

        @Override
        public void execute(final Object message) throws Exception {
            masterMediaGateway.tell(new org.mobicents.servlet.restcomm.mgcp.CreateMediaSession(), source);
        }
    }

    private final class AcquiringMasterConferenceEndpoint extends AbstractAction {

        public AcquiringMasterConferenceEndpoint(final ActorRef source) {
            super(source);
        }

        @Override
        public void execute(final Object message) throws Exception {
            masterMediaGateway.tell(new CreateConferenceEndpoint(masterMediaSession, masterConfernceEndpointIdName), super.source);
        }
    }

    private final class AcquiringRemoteConnectionWithMasterMS extends AbstractAction {

        public AcquiringRemoteConnectionWithMasterMS(final ActorRef source) {
            super(source);
        }

        @Override
        public void execute(final Object message) throws Exception {
            masterMediaGateway.tell(new CreateConnection(masterMediaSession), source);
        }
    }

    private final class InitializingRemoteConnectionWithMasterMS extends AbstractAction {

        public InitializingRemoteConnectionWithMasterMS(ActorRef source) {
            super(source);
        }

        @SuppressWarnings("unchecked")
        @Override
        public void execute(final Object message) throws Exception {
            final MediaGatewayResponse<ActorRef> response = (MediaGatewayResponse<ActorRef>) message;
            connectionWithMasterMS = response.get();
            connectionWithMasterMS.tell(new Observe(source), source);
            logger.info("^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^ InitializingRemoteConnectionWithMasterMS -  - received connectionWithMasterMS:"+connectionWithMasterMS+" | masterConfernceEndpoint: "+masterConfernceEndpoint+" ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^");
            connectionWithMasterMS.tell(new InitializeConnection(masterConfernceEndpoint), source);
        }
    }

    private final class OpeningRemoteConnectionWithMasterMS extends AbstractAction {
        public OpeningRemoteConnectionWithMasterMS(final ActorRef source) {
            super(source);
        }

        @Override
        public void execute(final Object message) throws Exception {
            logger.info("OpeningRemoteConnectionWithMasterMS...");
            final ConnectionDescriptor descriptor = new ConnectionDescriptor(localMediaServerSdp);
            OpenConnection open = new OpenConnection(descriptor, ConnectionMode.SendRecv, false);
            connectionWithMasterMS.tell(open, source);
        }
    }

    private final class UpdatingRemoteConnectionWithLocalMS extends AbstractAction {
        public UpdatingRemoteConnectionWithLocalMS(final ActorRef source) {
            super(source);
        }

        @Override
        public void execute(final Object message) throws Exception {
            final ConnectionDescriptor descriptor = new ConnectionDescriptor(masterMediaServerSdp);
            final UpdateConnection update = new UpdateConnection(descriptor);
            connectionWithLocalMS.tell(update, source);
        }
    }

    private final class AcquiringMasterIVREndpoint extends AbstractAction {

        public AcquiringMasterIVREndpoint(final ActorRef source) {
            super(source);
        }

        @Override
        public void execute(final Object message) throws Exception {
            masterMediaGateway.tell(new CreateIvrEndpoint(masterMediaSession, masterIVREndpointIdName), super.source);
        }
    }

    /*private final class Opening extends AbstractAction {
        public Opening(final ActorRef source) {
            super(source);
        }

        @Override
        public void execute(final Object message) throws Exception {
            final OpenLink request = (OpenLink) message;
            final String sessionId = Integer.toString(localMediaSession.id());
            final CallIdentifier callId = new CallIdentifier(sessionId);
            final jain.protocol.ip.mgcp.message.CreateConnection crcx = new jain.protocol.ip.mgcp.message.CreateConnection(source, callId, localConfernceEndpoint, request.mode());
            crcx.setNotifiedEntity(agent);
            crcx.setSecondEndpointIdentifier(masterIVREndpointId);
            localMediaGateway.tell(crcx, source);
            // Make sure we don't wait for a response indefinitely.
            getContext().setReceiveTimeout(Duration.create(timeout, TimeUnit.MILLISECONDS));
        }
    }*/

    private final class Active extends AbstractAction {

        public Active(final ActorRef source) {
            super(source);
        }

        @Override
        public void execute(final Object message) throws Exception {
            logger.info("CMRC is ACTIVE NOW...");
            updateConferenceStatus("RUNNING");
            if(!isThisMaster){
                // Stop the background music if present
                onStopMediaGroup(new StopMediaGroup(), self(), self());

                playBeepOnEnter(source);
                // enter slave record in MRB resource table
                addNewSlaveRecord();
            }
            broadcast(new ConferenceMediaResourceControllerStateChanged(ConferenceMediaResourceControllerStateChanged.MediaServerControllerState.ACTIVE));
        }
    }

    private class Stopping extends AbstractAction {

        public Stopping(ActorRef source) {
            super(source);
        }

        @Override
        public void execute(Object message) throws Exception {
            logger.info("CMRC is STOPPING NOW...");
            if(isThisMaster){
                logger.info("CMRC is STOPPING Master NOW...");
                updateMasterPresence(false);
                if(!areAnySlavesConnectedToThisConferenceEndpoint){
                    updateConferenceStatus("COMPLETED");
                    // Destroy Media Group
                    mediaGroup.tell(new StopMediaGroup(), super.source);
                }
            }else{
                logger.info("CMRC is STOPPING Slave NOW...");
                removeSlaveRecord();
                //check if it is last to leave in entire cluster then distroymaster conf EP as well
                if(!isMasterPresence() && noOfConnectedSlaves < 2){
                    logger.info("Going to Detroy Master conference EP..");
                    updateConferenceStatus("COMPLETED");
                    masterConfernceEndpoint.tell(new DestroyEndpoint(), super.source);
                    // Destroy Media Group
                    mediaGroup.tell(new StopMediaGroup(), super.source);
                }
            }
        }
    }

    private abstract class FinalState extends AbstractAction {

        public FinalState(ActorRef source) {
            super(source);
        }

        @Override
        public void execute(Object message) throws Exception {
        }
   }

    private final class Inactive extends FinalState {

        public Inactive(final ActorRef source) {
            super(source);
        }

    }

    private final class Failed extends FinalState {

        public Failed(final ActorRef source) {
            super(source);
        }

    }

    @Override
    public void postStop() {
        // Cleanup resources
        cleanup();

        // Clean observers
        observers.clear();

        // Terminate actor
        getContext().stop(self());
    }

    protected void cleanup() {
        if (connectionWithLocalMS != null) {
            context().stop(connectionWithLocalMS);
            connectionWithLocalMS = null;
        }

        if (connectionWithMasterMS != null) {
            context().stop(connectionWithMasterMS);
            connectionWithMasterMS = null;
        }
    }

    /*
     * private Utility Functions
     *
     */

    private void playBeepOnEnter(final ActorRef source) throws URISyntaxException{
        //TODO: read it from config after testing
        String path = "/restcomm/audio/";
        String entryAudio = "beep.wav";
        path += entryAudio == null || entryAudio.equals("") ? "beep.wav" : entryAudio;
        URI uri = null;
        uri = UriUtils.resolve(new URI(path));
        final Play play = new Play(uri, 1);
        onPlay(play, self(), sender());
    }

    private void playBeepOnExit(final ActorRef source) throws URISyntaxException{
        //TODO: read it from config after testing
        String path = "/restcomm/audio/";
        String entryAudio = "alert.wav";
        path += entryAudio == null || entryAudio.equals("") ? "beep.wav" : entryAudio;
        URI uri = null;
        uri = UriUtils.resolve(new URI(path));
        final Play play = new Play(uri, 1);
        onPlay(play, self(), sender());
    }
    /*
     * Database Utility Functions
     *
     */
    private void addNewSlaveRecord() {
        logger.info("addNewSlaveRecord: conferenceSid: "+conferenceSid+" localMsId: "+localMsId);
        final MediaResourceBrokerDao dao= storage.getMediaResourceBrokerDao();
        final MediaResourceBrokerEntity.Builder builder = MediaResourceBrokerEntity.builder();

        builder.setConferenceSid(conferenceSid);
        builder.setSlaveMsId(localMsId);
        builder.setBridgedTogether(true);

        entity = builder.build();
        dao.addMediaResourceBrokerEntity(entity);
    }

    private void updateMasterIVREndpointId(){
        if(cdr != null){
            logger.info("updateMasterIVREndpointId: name: "+masterIVREndpointIdName);
            final ConferenceDetailRecordsDao dao = storage.getConferenceDetailRecordsDao();
            cdr = dao.getConferenceDetailRecord(conferenceSid);
            cdr = cdr.setMasterIVREndpointId(masterIVREndpointIdName);
            dao.updateConferenceDetailRecordMasterIVREndpointID(cdr);
        }
    }

    private void updateMasterConferenceEndpointId(){
        if(cdr != null){
            logger.info("updateMasterConferenceEndpointId: localConfernceEndpointId.getLocalEndpointName(): "+masterConfernceEndpointId.getLocalEndpointName());
            final ConferenceDetailRecordsDao dao = storage.getConferenceDetailRecordsDao();
            cdr = dao.getConferenceDetailRecord(conferenceSid);
            cdr = cdr.setMasterConfernceEndpointId(masterConfernceEndpointId.getLocalEndpointName());
            dao.updateConferenceDetailRecordMasterEndpointID(cdr);
        }
    }

    private void updateMasterPresence(boolean masterPresent){
        if(cdr != null){
            logger.info("updateMasterPresence called");
            final ConferenceDetailRecordsDao dao = storage.getConferenceDetailRecordsDao();
            cdr = dao.getConferenceDetailRecord(conferenceSid);
            cdr = cdr.setMasterPresent(masterPresent);
            dao.updateMasterPresent(cdr);
        }
    }

    private void updateConferenceStatus(String status){
        if(cdr != null){
            logger.info("updateConferenceStatus called");
            final ConferenceDetailRecordsDao dao = storage.getConferenceDetailRecordsDao();
            cdr = dao.getConferenceDetailRecord(conferenceSid);
            cdr = cdr.setStatus(status);
            dao.updateConferenceDetailRecordStatus(cdr);
        }
    }

    private void removeSlaveRecord() throws ParseException {
        final MediaResourceBrokerDao dao= storage.getMediaResourceBrokerDao();
        dao.removeMediaResourceBrokerEntity(new MediaResourceBrokerEntityFilter(conferenceSid.toString(), localMsId));
    }

    private boolean areAnySlavesConnectedToThisConferenceEndpoint(){
        final MediaResourceBrokerDao dao= storage.getMediaResourceBrokerDao();
        List<MediaResourceBrokerEntity> slaves = dao.getConnectedSlaveEntitiesByConfSid(conferenceSid);
        if(slaves != null && !slaves.isEmpty()){
            noOfConnectedSlaves = slaves.size();
            return true;
        }
        return false;
    }

    private boolean isMasterPresence(){
        boolean masterPresent = true;
        if(cdr != null){
            final ConferenceDetailRecordsDao dao = storage.getConferenceDetailRecordsDao();
            cdr = dao.getConferenceDetailRecord(conferenceSid);
            masterPresent = cdr.isMasterPresent();
            logger.info("isMasterPresence called : is masterPresent?: "+ masterPresent);
        }
        return masterPresent;
    }
}