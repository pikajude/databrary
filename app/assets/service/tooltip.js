'use strict';

module.factory('tooltipService', [
  '$rootScope', 'ArrayHelper', '$timeout', function ($rootScope, ArrayHelper, $timeout) {
    var tooltips = new ArrayHelper([]);
    var $doc = $(document);

    var HOVER_DELAY = 500;

    tooltips.types = ['blue', 'green', 'red', 'orange', 'yellow', 'purple'];

    var padW = 20;
    var padH = 15;

    //

    tooltips.newCatalog('id');

    var sequence = 0;
    tooltips.newTransform(function (tooltip) {
      tooltip.id = tooltip.id || 'tooltip-' + sequence++;
      tooltip.cls = tooltip.cls || '';
      tooltip.style = angular.isObject(tooltip.style) ? tooltip.style : {};
      tooltip.type = tooltips.types.indexOf(tooltip.type) !== -1 ? tooltip.type : 'blue';
      tooltip.enabled = angular.isUndefined(tooltip.enabled) || !!tooltip.enabled;
      tooltip.visible = !!tooltip.visible;
      tooltip.live = tooltip.live || false;

      return tooltip;
    });

    //

    tooltips.add = function (tooltip) {
      var newTooltip = ArrayHelper.prototype.add.call(this, tooltip);

      if (newTooltip) {
        tooltips.target(newTooltip);
      }

      return newTooltip;
    };

    //

    tooltips.remove = function (tooltip) {
      removeEvents(tooltip);

      return ArrayHelper.prototype.remove.call(this, tooltip);
    };

    //

    tooltips.update = function (tooltip, obj) {
      var newtooltip = ArrayHelper.prototype.update.call(this, tooltip, obj);

      if (newtooltip) {
        tooltips.target(newtooltip);
      }

      return newtooltip;
    };

    //

    tooltips.enable = function (tooltip) {
      return tooltips.toggle(tooltip, 'enabled', true);
    };

    tooltips.disable = function (tooltip) {
      tooltips.hide(tooltip);

      return tooltips.toggle(tooltip, 'enabled', false);
    };

    //

    tooltips.show = function (tooltip, event) {
      if (!tooltip.enabled) {
        return undefined;
      }

      tooltips.position(tooltip, [event.clientX, event.clientY]);

      return tooltips.toggle(tooltip, 'visible', true);
    };

    tooltips.hide = function (tooltip) {
      tooltips.position(tooltip, false);

      return tooltips.toggle(tooltip, 'visible', false);
    };

    tooltips.position = function (tooltip, loc) {
      if (loc === false) {
        return;
      }

      var $t = tooltip.$target,
        $e = $('#' + tooltip.id),
        $w = $(window);

      if (!loc) {
        loc = [
          $t.offset().left,
          $t.offset().top
        ];
      }

      var center = {
        left: loc[0],
        top: loc[1],
        right: $w.width() - loc[0],
        bottom: $w.height() - loc[1]
      };

      tooltip.position = [];

      if (center.left > center.right) {
        tooltip.style.left = (loc[0] + $(window).scrollLeft() - $e.outerWidth() + padW) + 'px';
        tooltip.position.push('left');
      } else {
        tooltip.style.left = (loc[0] + $(window).scrollLeft() - padW) + 'px';
        tooltip.position.push('right');
      }

      if (center.top > center.bottom) {
        tooltip.style.top = (loc[1] + $(window).scrollTop() - $e.outerHeight() - padH) + 'px';
        tooltip.position.push('top');
      } else {
        tooltip.style.top = (loc[1] + $(window).scrollTop() + padH) + 'px';
        tooltip.position.push('bottom');
      }
    };

    //

    var getTargetEvents = function (tooltip) {
      if (!tooltip.$target) {
        return [];
      }

      var focusElements = ['INPUT', 'SELECT', 'TEXTAREA'],
        namespace = '.tooltipTarget';

      if (!angular.isString(tooltip.$target) && focusElements.indexOf(tooltip.$target.prop('tagName')) >= 0) {
        return [
            'focusin' + namespace + '-' + tooltip.id,
            'focusout' + namespace + '-' + tooltip.id
        ];
      }

      return [
          'mouseenter' + namespace + '-' + tooltip.id,
          'mouseleave' + namespace + '-' + tooltip.id
      ];
    };

    var removeEvents = function (tooltip) {
      if (tooltip.$target) {
        if (tooltip.live) {
          $doc.off(getTargetEvents(tooltip).join(' '), tooltip.$target);
        }
        else {
          tooltip.$target.unbind(getTargetEvents(tooltip).join(' '));
        }
      }
    };

    tooltips.target = function (tooltip, $newTarget) {
      if (tooltips.index(tooltip) == -1) {
        return undefined;
      }

      removeEvents(tooltip);

      tooltip.$target = angular.isDefined($newTarget) ? $newTarget : tooltip.$target;

      var $target = tooltip.$target;

      if (!tooltip.live && $target.length === 0) {
        tooltips.disable(tooltip);
        return (tooltip.$target = false);
      }

      var events = getTargetEvents(tooltip);

      var timeout;

      if (tooltip.live) {
        $doc.on(events[0], tooltip.$target, function (event) {
	  var target = $(event.target);
          timeout = $timeout(function () {
	    if (target.is(tooltip.$target)) // may have changed
	      tooltips.show(tooltip, event);
          }, angular.isNumber(tooltip.delay) ? tooltip.delay : HOVER_DELAY);
        });

        $doc.on(events[1], tooltip.$target, function (event) {
          $rootScope.$apply(function () {
            $timeout.cancel(timeout);
            tooltips.hide(tooltip, event);
          });
        });
      } else {
        $target.bind(events[0], function (event) {
          timeout = $timeout(function () {
            tooltips.show(tooltip, event);
          }, angular.isNumber(tooltip.delay) ? tooltip.delay : HOVER_DELAY);
        });

        $target.bind(events[1], function (event) {
          $rootScope.$apply(function () {
            $timeout.cancel(timeout);
            tooltips.hide(tooltip, event);
          });
        });
      }

      tooltips.hide(tooltip);

      return tooltip;
    };

    //

    $rootScope.$watch(function () {
      angular.forEach(tooltips, function (tooltip) {
        if (!angular.isString(tooltip.$target) &&
	    ((document.contains && !document.contains(tooltip.$target[0])) ||
	    tooltip.$target.closest(document.documentElement).length === 0)) {
	     removeEvents(tooltip);
	     tooltips.remove(tooltip);
        }
      });
    });

    tooltips.clear = function () {
      angular.forEach(tooltips, function (tooltip) {
        tooltips.hide(tooltip);
      });
    };

    $rootScope.$on('$routeChangeStart', tooltips.clear);

    //

    return tooltips;
  }
]);
