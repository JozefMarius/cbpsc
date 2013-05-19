SFTree {
	var <>corpus, <>anchorPath, <>nodes, <>trackbacks;

	*new { |corpus, path, verbose=nil|
		^super.new.initSFTree(corpus, path, verbose)
	}

	initSFTree { |corpus, path, verbose|
		this.corpus = corpus;
		this.anchorPath = path;
		this.nodes = Dictionary[];
		this.trackbacks = Dictionary[];
		^this
	}

	addRootNode { |filePath, sfID, tRatio, sfg=0, uniqueFlag=nil, verbose=nil|

		var uniqueflag, sndFile, duration, chnls, synthdef;

		// (sndSubdir.isNil).if {
		// 	joinedPath = this.anchorPath +/+ "snd" +/+ filename;
		// } {
		// 	joinedPath = this.anchorPath +/+ "snd" +/+ sndSubdir +/+ filename;
		// };
		uniqueflag = uniqueFlag ? 1000000.rand;

		sndFile = SoundFile.new; sndFile.openRead(filePath); sndFile.close;

// 		sndFile.numFrames.asFloat.postln;
// 		sndFile.sampleRate.asFloat.postln;

		duration = sndFile.duration; //(sndFile.numFrames.asFloat / sndFile.sampleRate.asFloat);
		Post << "dur: " << duration << "\n";
		chnls = sndFile.numChannels;

		synthdef = (chnls == 1).if { "monoSamplerNRT" } { "stereoSamplerNRT" };
		Post << "sfID: " << sfID << "\n";
		this.nodes.add(sfID -> SamplerNode.new(filePath, synthdef, duration, uniqueflag, chnls, sfg, tRatio, sfID));
		this.nodes[sfID].postln;
		this.corpus.mapIDToSF(sfID, filePath, sfg);
		this.trackbacks.add(sfID -> [filePath, duration, tRatio, synthdef]);
		^this.nodes[sfID]
	}

	addChildNode { |parentID, childID, tRatio, sfg, synthdef, params, uniqueFlag=nil|

		var uniqueflag, parentNode;

		uniqueflag = uniqueFlag ? 1000000.rand;
		(this.nodes[parentID].isNil).if {
			Post << "Parent (" << parentID << ") does not exist. Cannot create child (" << childID << ").\n";
			^nil;
		} {
			parentNode = this.nodes[parentID];
		};

		this.nodes.add(childID -> EfxNode.new(synthdef, params, parentNode.duration, uniqueflag, parentNode.channels, sfg, parentNode.tRatio, childID, parentID));
		this.nodes[parentID].postln;
		this.corpus.mapIDToSF(childID, this.nodes[parentID].sfPath, sfg);
		this.trackbacks.add(childID -> [synthdef, params]);
		^this.nodes[this.nodes[childID].sfID]
	}
}


SFNode {
	var <>synth, <>params, <>duration, <>uniqueID, <>channels, <>group, <>tRatio, <>sfID, <>unitSegments, <>unitAmps, <>unitMFCCs;

	*new { |synthname, params=nil, duration= -1, uniqueID= -1, channels=1, group=0, tRatio=1.0, sfID= -1, verbose=nil|
		^super.new.initSFNode(synthname, params, duration, uniqueID, channels, group, tRatio, sfID, verbose)
	}

	initSFNode { |synthname, params, duration, uniqueID, channels, group, tRatio, sfID, verbose|

		this.synth = synthname;
		this.params = params;
		this.duration = duration;
		this.uniqueID = uniqueID;
		this.channels = channels;
		this.group = group;
		this.tRatio = tRatio;
		this.sfID = sfID;
		this.unitSegments = List[]; // create an empty container for unit bounds and tags
		this.unitAmps = Dictionary[];
		this.unitMFCCs = Dictionary[];
	}

	addOnsetAndDurPair { |onset, duration, relID=nil|

		// Post << "add onset: " << onset << " and duration: " << duration << "\n";

		relID = (relID.isNil).if { this.unitSegments.size } { relID };

		// Post << "rel. ID is: " << relID << "\n";

		(this.unitSegments[relID].isNil).if {
			this.unitSegments = this.unitSegments ++ [ SFU.new(0.max(onset).min((this.duration / this.tRatio))) ];
		} {
			this.unitSegments[relID] =  0.max(onset).min(this.duration);
		};
		this.unitSegments[relID].duration = ((this.duration / this.tRatio) - this.unitSegments[relID].onset).min(0.max(duration));
		// Post << this.unitSegments[relID] << "\n";
		^this.unitSegments[relID]
	}

	updateUnitSegmentParams{ |relID, onset=nil, duration=nil, tag=nil|

		(onset.isNil.not).if { this.unitSegments[relID].onset = onset } { };
		(duration.isNil.not).if { this.unitSegments[relID].duration = duration } { };
		(tag.isNil.not).if { this.unitSegments[relID].tag = tag } { };
		^this.unitSegments[relID]
	}

	calcRemainingDur { ^this.duration - this.unitSegments.last.onset }

	sortSegmentsList { this.unitSegments = this.unitSegments.sort({ |a,b| a.onset < b.onset }); ^this.unitSegments }

	addMetadataForRelID { |relID, amps, mfccs|

		((relID.isNil.not) && (amps.isNil.not)).if { this.unitAmps.add(relID -> amps) } { }; // in ML format
		((relID.isNil.not) && (mfccs.isNil.not)).if { this.unitMFCCs.add(relID -> mfccs) } { }; // in ML format

	}
}


SamplerNode : SFNode {
	var <>sfPath;

	*new { |sfpath, synthname, duration= -1, uniqueID= -1, channels=1, group=0, tRatio=1.0, sfID= -1, verbose=nil|
		^super.new.initSFNode(synthname, nil, duration, uniqueID, channels, group, tRatio, sfID).initSamplerNode(sfpath)
	}

	initSamplerNode { |sfpath|
		"Assigning sfPath to SamplerNode!".postln;
		this.sfPath = sfpath;
		^this
	}

	jsonRepr {
		^Dictionary["path" -> this.sfPath,
			"synth" -> this.synth,
			"params" -> this.params,
			"duration" -> this.duration,
			"uniqueID" -> this.uniqueID,
			"channels" -> this.channels,
			"group" -> this.group,
			"tRatio" -> this.tRatio,
			"sfID" -> this.sfID]
	}
}


EfxNode : SFNode {
	var <>parentID;

	*new { |synthname, params, duration= -1, uniqueID= -1, channels=1, group=0, tRatio=1.0, childID= -1, parentID= -1, verbose=nil|
		^super.new.initSFNode(synthname, params, duration, uniqueID, channels, group, tRatio, childID).initEfxNode(parentID)
	}

	initEfxNode { |parentID|
		this.parentID = parentID;
		^this
	}

	jsonRepr {
		^Dictionary["parentID" -> this.parentID,
			"synth" -> this.synth,
			"params" -> this.params,
			"duration" -> this.duration,
			"uniqueID" -> this.uniqueID,
			"channels" -> this.channels,
			"group" -> this.group,
			"tRatio" -> this.tRatio,
			"sfID" -> this.sfID]
	}
}

SFU {
	var <>onset, <>duration, <>tag;

	*new { |onset=0, duration=0, tag=0, verbose=nil|
		^super.new.initSFU(onset, duration, tag)
	}

	initSFU { |onset, duration, tag|
		this.onset = onset;
		this.duration = duration;
		this.tag = tag;
		^this
	}
}