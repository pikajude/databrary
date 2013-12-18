define(['app/config/module'], function (module) {
	'use strict';

	module.factory('Party', ['$rootScope', 'resourceService', function ($rootScope, resourceService) {
		return resourceService('party', '/api/party/:id', {
			id: '@id'
		}, [
			'volumes',
			'comments',
			'parents',
			'children',
//			'tags',
//			'network',
			'funding'
		]);
	}]);
});
